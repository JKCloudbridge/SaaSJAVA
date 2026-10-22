package app.platform.metadata.internal;

import app.platform.expression.ExpressionEngine;
import app.platform.expression.Limits;
import app.platform.metadata.DefinitionKind;
import app.platform.metadata.FieldConfiguration.Dependency;
import app.platform.metadata.FieldConfiguration.PicklistConfiguration;
import app.platform.metadata.FieldDefinition;
import app.platform.metadata.FieldType;
import app.platform.metadata.ObjectDefinition;
import app.platformapi.ApiException;
import app.platformapi.FieldSettings;
import app.platformapi.PicklistDependency;
import app.platformapi.PicklistOption;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    /**
     * The objects a shipped solution brings (Functional track FA1, ADR-0181). They are platform definitions in the same
     * format as the standard objects, kept next to the solution that needs them; this module reads them by the pattern
     * and knows nothing of what any solution is, so a solution's words never enter the metadata module. The module
     * that loads the solutions checks that every such file belongs to one of them.
     */
    static final String SHIPPED_LOCATION = "classpath:solutions/*/objects/*.yml";
    static final String SYSTEM_FIELDS_FILE = "_system-fields.yml";

    /** What a plan feature key looks like (the same shape the {@code feature} table checks). */
    private static final Pattern FEATURE_KEY = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    /** The parts of the platform that may own the records of a standard object. */
    static final Set<String> OWNERS = Set.of("identity", "security", "licensing", "metadata");

    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,39}");
    private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    private static final int MAX_FIELDS = 200;
    private static final int MAX_PICKLIST_VALUES = 1000;

    /** Reads the text of a formula a definition file might hold; stateless, so one engine serves every read. */
    private static final ExpressionEngine SYNTAX = new ExpressionEngine(Limits.standard());

    private static final Set<String> OBJECT_KEYS = Set.of("apiName", "label", "pluralLabel", "description",
            "managedBy", "extensible", "requiresFeature", "retainRecords", "sharingDefault", "fields");
    private static final Set<String> FILE_KEYS = Set.of("fields");
    private static final Set<String> FIELD_KEYS = Set.of("apiName", "label", "description", "type", "required",
            "unique", "indexed", "default", "settings", "retired", "organizationValues", "codeValues",
            "platformWritten");
    private static final Set<String> SETTING_KEYS = Set.of("maxLength", "digits", "precision", "scale", "values",
            "targetObject", "expression", "resultType", "prefix", "startAt", "width", "onDelete", "reparentable",
            "listLabel", "dependency");
    private static final Set<String> VALUE_KEYS = Set.of("value", "label", "active");
    private static final Set<String> DEPENDENCY_KEYS = Set.of("controllingField", "allowed");

    private StandardMetadataLoader() {
    }

    /** Reads and checks every file; throws {@link IllegalStateException} naming the first mistake. */
    static StandardMetadata load() {
        return load(LOCATION, SHIPPED_LOCATION);
    }

    /**
     * The same for the files the patterns find (the tests point it at deliberately broken files). The system fields
     * file is looked for in all of them; an object file name used twice is refused.
     */
    static StandardMetadata load(String... locations) {
        try {
            Resource systemFile = null;
            Map<String, Resource> objectFiles = new TreeMap<>();
            for (String location : locations) {
                for (Resource file : new PathMatchingResourcePatternResolver().getResources(location)) {
                    String name = file.getFilename();
                    if (name == null) {
                        continue;
                    }
                    if (name.equals(SYSTEM_FIELDS_FILE)) {
                        systemFile = file;
                    } else if (objectFiles.put(name, file) != null) {
                        throw new IllegalStateException(name + " is defined twice (two folders hold an object of the "
                                + "same name)");
                    }
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
            String requiresFeature = node.optionalText("requiresFeature");
            if (requiresFeature != null && !FEATURE_KEY.matcher(requiresFeature).matches()) {
                throw new IllegalStateException(where + ": 'requiresFeature' is a plan feature key (lower case "
                        + "letters, digits and hyphens)");
            }
            boolean retainRecords = node.flag("retainRecords", false);
            if (managedBy != null && (requiresFeature != null || retainRecords)) {
                throw new IllegalStateException(where + ": an object another part of the platform owns cannot "
                        + "be hidden by a plan feature or keep its records (its records are not served by the "
                        + "record store)");
            }
            String sharingDefault = node.optionalText("sharingDefault");
            if (sharingDefault != null && (!"PRIVATE".equals(sharingDefault) || managedBy != null)) {
                throw new IllegalStateException(where + ": 'sharingDefault' is PRIVATE or left out, and only an "
                        + "object the record store serves has one");
            }
            List<FieldDefinition> own = fields(node, apiName, DefinitionKind.STANDARD);
            if (managedBy != null && own.stream().anyMatch(field -> field.organizationValues()
                    || field.platformWritten())) {
                throw new IllegalStateException(where + ": an object another part of the platform owns cannot have "
                        + "a field the organization owns the values of or only the platform writes");
            }
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
            ObjectDefinition made = new ObjectDefinition(apiName, label, plural, description, DefinitionKind.STANDARD,
                    managedBy, extensible, 0, all, requiresFeature, retainRecords, sharingDefault);
            checkDependencies(where, made);
            return made;
        }

        /**
         * A dependent picklist is judged by the rules the dependency graph applies to an organization's (ADR-0177), now
         * that the whole object is known; and a platform picklist whose controlling field is a list the organization
         * owns must be one too, or the organization could add a value to the controlling field and never pair it.
         */
        private static void checkDependencies(String where, ObjectDefinition object) {
            for (FieldDefinition field : object.fields()) {
                List<PicklistDependencies.Problem> problems = PicklistDependencies.problems(object, field);
                if (!problems.isEmpty()) {
                    throw new IllegalStateException(where + " (" + field.apiName() + "): "
                            + problems.get(0).message());
                }
                Dependency dependency = PicklistDependencies.dependencyOf(field);
                if (dependency == null) {
                    continue;
                }
                FieldDefinition controlling = object.field(dependency.controllingField()).orElse(null);
                if (controlling == null) {
                    throw new IllegalStateException(where + " (" + field.apiName() + "): the field it depends on, '"
                            + dependency.controllingField() + "', is not a field of the object");
                }
                if (controlling.organizationValues() && !field.organizationValues()) {
                    throw new IllegalStateException(where + " (" + field.apiName() + "): the list of '"
                            + controlling.apiName() + "' belongs to the organization, so the list of this dependent "
                            + "picklist must too ('organizationValues: true')");
                }
            }
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
                    MAX_PICKLIST_VALUES, SYNTAX);
            boolean indexed;
            FieldRules.Checked checked;
            try {
                checked = FieldRules.check(type, node.flag("required", false), node.flag("unique", false),
                        node.optionalScalar("default"), settings, context);
                indexed = FieldRules.checkIndexed(type, node.flag("indexed", false));
            } catch (ApiException e) {
                throw new IllegalStateException(where + ": " + e.fields());
            }
            boolean organizationValues = node.flag("organizationValues", false);
            boolean codeValues = node.flag("codeValues", false);
            boolean platformWritten = node.flag("platformWritten", false);
            checkFlags(where, kind, type, checked, organizationValues, codeValues, platformWritten);
            return new FieldDefinition(objectApiName, apiName, label, description, kind, type, checked.required(),
                    checked.unique(), checked.defaultValue(), checked.configuration(), node.flag("retired", false), 0,
                    indexed, false, false, organizationValues, codeValues, platformWritten);
        }

        /** What the three attributes of the Functional track need of the field that carries them (ADR-0182, 0183). */
        private static void checkFlags(String where, DefinitionKind kind, FieldType type, FieldRules.Checked checked,
                boolean organizationValues, boolean codeValues, boolean platformWritten) {
            if ((organizationValues || codeValues) && type != FieldType.PICKLIST) {
                throw new IllegalStateException(where + ": 'organizationValues' and 'codeValues' belong to a "
                        + "single-value picklist");
            }
            if ((organizationValues || codeValues || platformWritten) && kind == DefinitionKind.SYSTEM) {
                throw new IllegalStateException(where + ": a system field has none of 'organizationValues', "
                        + "'codeValues' and 'platformWritten'");
            }
            if (codeValues && !FieldRules.codeProblems(((PicklistConfiguration) checked.configuration()).values())
                    .isEmpty()) {
                throw new IllegalStateException(where + ": every value of a picklist with 'codeValues' is a code of "
                        + "1 to 10 letters, digits, hyphens or underscores");
            }
            if (platformWritten && (type != FieldType.TEXT && type != FieldType.BOOLEAN || checked.required()
                    || checked.defaultValue() != null)) {
                throw new IllegalStateException(where + ": a field only the platform writes is a text field or a "
                        + "yes/no field, is not required and has no default (a member could never fill it in)");
            }
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
                    node.optionalInteger("width"), node.optionalText("onDelete"),
                    node.has("reparentable") ? node.flag("reparentable", false) : null,
                    node.optionalText("listLabel"), dependency(node));
        }

        /** The rule of a dependent picklist as the file writes it: the controlling field and the table of pairs. */
        private PicklistDependency dependency(YamlNode settings) {
            YamlNode node = settings.node("dependency", DEPENDENCY_KEYS);
            if (node == null) {
                return null;
            }
            Map<String, List<String>> allowed = new LinkedHashMap<>();
            Map<?, ?> table = node.table("allowed");
            if (table != null) {
                for (Map.Entry<?, ?> row : table.entrySet()) {
                    List<String> listed = new ArrayList<>();
                    if (row.getValue() instanceof List<?> items) {
                        items.forEach(item -> listed.add(String.valueOf(item)));
                    } else if (row.getValue() != null) {
                        throw new IllegalStateException(node.where() + ": the values for '" + row.getKey()
                                + "' are a list");
                    }
                    allowed.put(String.valueOf(row.getKey()), listed);
                }
            }
            return new PicklistDependency(node.text("controllingField"), allowed);
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
