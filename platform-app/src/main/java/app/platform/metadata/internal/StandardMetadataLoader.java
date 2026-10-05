package app.platform.metadata.internal;

import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.FieldSettings;
import app.platformapi.PicklistOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Reads the definition files of the standard objects and fields and checks them completely (ADR-0059). One file
 * per object ({@code Account.yml}) and one file for the fields every object has ({@code _system-fields.yml}), all under
 * {@code metadata/standard} on the class path. The format and the recipes for changing it are in
 * {@code docs/metadata-guide.md}.
 *
 * <p>The checking is strict on purpose: an unknown key, a field that breaks the rules of its type, a lookup to an
 * object that does not exist, a name used twice or a file whose name does not match its object all stop the
 * application from starting, with a message that names the file and the place. A mistake in a definition file is found
 * by the build, never by an organization.
 */
final class StandardMetadataLoader {

    static final String LOCATION = "classpath:metadata/standard/*.yml";
    static final String SYSTEM_FIELDS_FILE = "_system-fields.yml";

    /** The parts of the platform that may own the records of a standard object. */
    static final Set<String> OWNERS = Set.of("identity", "security", "licensing");

    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,39}");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    private static final int MAX_FIELDS = 200;
    private static final int MAX_PICKLIST_VALUES = 1000;

    private static final Set<String> OBJECT_KEYS = Set.of("apiName", "label", "pluralLabel", "description",
            "managedBy", "extensible", "fields");
    private static final Set<String> FILE_KEYS = Set.of("fields");
    private static final Set<String> FIELD_KEYS = Set.of("apiName", "label", "description", "type", "required",
            "unique", "default", "settings", "retired");
    private static final Set<String> SETTING_KEYS = Set.of("maxLength", "digits", "precision", "scale", "values",
            "targetObject", "expression", "resultType", "prefix", "startAt", "width");
    private static final Set<String> VALUE_KEYS = Set.of("value", "label", "active");

    private StandardMetadataLoader() {
    }

    /** Reads and checks every file; throws {@link IllegalStateException} naming the first mistake. */
    static StandardMetadata load() {
        return load(LOCATION);
    }

    /** The same for the files a pattern finds (the tests point it at deliberately broken files). */
    static StandardMetadata load(String location) {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(location);
            Resource systemFile = null;
            Map<String, Resource> objectFiles = new TreeMap<>();
            for (Resource file : files) {
                String name = file.getFilename();
                if (name == null) {
                    continue;
                }
                if (name.equals(SYSTEM_FIELDS_FILE)) {
                    systemFile = file;
                } else {
                    objectFiles.put(name, file);
                }
            }
            if (systemFile == null) {
                throw new IllegalStateException("metadata/standard/" + SYSTEM_FIELDS_FILE + " is missing");
            }
            Set<String> names = new HashSet<>();
            for (String file : objectFiles.keySet()) {
                names.add(file.substring(0, file.length() - ".yml".length()));
            }
            Reading reading = new Reading(names);
            List<FieldDefinition> system = reading.systemFields(read(systemFile));
            List<ObjectDefinition> objects = new ArrayList<>();
            for (Map.Entry<String, Resource> entry : objectFiles.entrySet()) {
                objects.add(reading.object(entry.getKey(), read(entry.getValue()), system));
            }
            objects.sort(Comparator.comparing(ObjectDefinition::apiName));
            return new StandardMetadata(objects, system);
        } catch (IOException e) {
            throw new IllegalStateException("The standard definition files could not be read", e);
        }
    }

    private static Object read(Resource file) {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(file);
        try {
            Map<String, Object> map = yaml.getObject();
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            // A YAML syntax mistake (often a colon followed by a space inside an unquoted text): say which file.
            throw new IllegalStateException(file.getFilename() + " is not valid YAML: " + e.getMessage(), e);
        }
    }

    /** One reading of all the files; it knows the names of all the objects so lookups can be checked. */
    private static final class Reading {

        private final Set<String> objectNames;

        Reading(Set<String> objectNames) {
            this.objectNames = objectNames;
        }

        List<FieldDefinition> systemFields(Object content) {
            YamlNode file = new YamlNode(SYSTEM_FIELDS_FILE, content, FILE_KEYS);
            return fields(file, "", DefinitionKind.SYSTEM);
        }

        ObjectDefinition object(String fileName, Object content, List<FieldDefinition> system) {
            String where = fileName;
            YamlNode node = new YamlNode(where, content, OBJECT_KEYS);
            String apiName = node.text("apiName");
            if (!OBJECT_NAME.matcher(apiName).matches()) {
                throw new IllegalStateException(where + ": 'apiName' is a capital letter followed by letters and "
                        + "digits (no underscore, never ending in __c)");
            }
            if (!(apiName + ".yml").equals(fileName)) {
                throw new IllegalStateException(where + ": the file must be called " + apiName + ".yml");
            }
            String label = limited(node, "label", 80);
            String plural = limited(node, "pluralLabel", 80);
            String description = node.optionalText("description") == null ? "" : limited(node, "description", 500);
            String managedBy = node.optionalText("managedBy");
            if (managedBy != null && !OWNERS.contains(managedBy)) {
                throw new IllegalStateException(where + ": 'managedBy' is one of " + OWNERS + " or left out");
            }
            boolean extensible = node.flag("extensible", true);
            List<FieldDefinition> own = fields(node, apiName, DefinitionKind.STANDARD);
            Set<String> used = new HashSet<>();
            List<FieldDefinition> all = new ArrayList<>();
            for (FieldDefinition field : system) {
                used.add(field.apiName().toLowerCase(Locale.ROOT));
                all.add(field.forObject(apiName));
            }
            for (FieldDefinition field : own) {
                if (!used.add(field.apiName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException(where + ": the field '" + field.apiName()
                            + "' is used twice (also by the system fields; names count without regard to case)");
                }
                all.add(field);
            }
            if (all.size() > MAX_FIELDS) {
                throw new IllegalStateException(where + ": more than " + MAX_FIELDS + " fields");
            }
            return new ObjectDefinition(apiName, label, plural, description, DefinitionKind.STANDARD, managedBy,
                    extensible, 0, all);
        }

        private List<FieldDefinition> fields(YamlNode parent, String objectApiName, DefinitionKind kind) {
            List<FieldDefinition> fields = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (YamlNode node : parent.nodes("fields", FIELD_KEYS)) {
                FieldDefinition field = field(node, objectApiName, kind);
                if (!seen.add(field.apiName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException(node.where() + ": the field '" + field.apiName()
                            + "' is used twice");
                }
                fields.add(field);
            }
            return fields;
        }

        private FieldDefinition field(YamlNode node, String objectApiName, DefinitionKind kind) {
            String apiName = node.text("apiName");
            if (!FIELD_NAME.matcher(apiName).matches()) {
                throw new IllegalStateException(node.where() + ": 'apiName' is a lower case letter followed by "
                        + "letters and digits (no underscore, never ending in __c)");
            }
            String where = node.where() + " (" + apiName + ")";
            String label = limited(node, "label", 80);
            String description = node.optionalText("description") == null ? "" : limited(node, "description", 500);
            FieldType type = FieldType.fromCode(node.text("type"))
                    .orElseThrow(() -> new IllegalStateException(where + ": 'type' is not a field type"));
            FieldSettings settings = settings(node);
            FieldRules.Context context = new FieldRules.Context(objectNames::contains, objectApiName, true, 0,
                    MAX_PICKLIST_VALUES);
            FieldRules.Checked checked;
            try {
                checked = FieldRules.check(type, node.flag("required", false), node.flag("unique", false),
                        node.optionalScalar("default"), settings, context);
            } catch (ApiException e) {
                throw new IllegalStateException(where + ": " + e.fields());
            }
            return new FieldDefinition(objectApiName, apiName, label, description, kind, type, checked.required(),
                    checked.unique(), checked.defaultValue(), checked.configuration(), node.flag("retired", false), 0);
        }

        private FieldSettings settings(YamlNode field) {
            YamlNode node = field.node("settings", SETTING_KEYS);
            if (node == null) {
                return FieldSettings.none();
            }
            List<PicklistOption> values = null;
            if (node.has("values")) {
                values = new ArrayList<>();
                for (YamlNode value : node.nodes("values", VALUE_KEYS)) {
                    values.add(new PicklistOption(value.text("value"), value.text("label"),
                            value.flag("active", true)));
                }
            }
            return new FieldSettings(node.optionalInteger("maxLength"), node.optionalInteger("digits"),
                    node.optionalInteger("precision"), node.optionalInteger("scale"), values,
                    node.optionalText("targetObject"), node.optionalText("expression"),
                    node.optionalText("resultType"), node.optionalText("prefix"), node.optionalLong("startAt"),
                    node.optionalInteger("width"));
        }

        private static String limited(YamlNode node, String key, int max) {
            String text = node.text(key).strip();
            if (text.isEmpty() && !key.equals("description")) {
                throw new IllegalStateException(node.where() + ": '" + key + "' is empty");
            }
            if (text.length() > max) {
                throw new IllegalStateException(node.where() + ": '" + key + "' is longer than " + max
                        + " characters");
            }
            return text;
        }
    }
}
