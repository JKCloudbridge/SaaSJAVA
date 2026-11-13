package app.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.architecture.fixtures.PoiOutsideTheSheetsModule;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How the library that reads workbook styles may be used (SH1B, ADR-0253): only the sheets module names it, no class
 * of its scratchpad module is on the class path or named anywhere, and no class of the sheets module opens a file by
 * name or writes one, because the upload reads from bytes and never touches the owner's file. Each rule is also held
 * to a class that breaks it, so it is known to fail when it should.
 */
@Tag("architecture")
class WorkbookLibraryRulesTest {

    private static final String[] LIBRARY = {"org.apache.poi..", "org.apache.xmlbeans..", "org.openxmlformats.."};
    private static final String[] SCRATCHPAD = {"org.apache.poi.hwpf..", "org.apache.poi.hslf..",
            "org.apache.poi.hsmf..",
        "org.apache.poi.hdgf..", "org.apache.poi.hpbf.."};

    private static JavaClasses platform;

    @BeforeAll
    static void importClasses() {
        platform = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("app.platform");
    }

    private static ArchRule libraryOnlyInTheSheetsModule() {
        return noClasses().that().resideOutsideOfPackage("app.platform.sheets..").should()
                .dependOnClassesThat().resideInAnyPackage(LIBRARY)
                .because("the library's types stop at the edge of the sheets module: a workbook's format never "
                        + "reaches the database or the API (ADR-0186, ADR-0253)").allowEmptyShould(true);
    }

    private static ArchRule noFileOpenedByName() {
        return noClasses().that().resideInAPackage("app.platform.sheets..").should().dependOnClassesThat()
                .haveFullyQualifiedName("java.io.File").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.io.FileInputStream").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.io.FileOutputStream").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.io.RandomAccessFile").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.nio.file.Files").orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.poi.openxml4j.opc.OPCPackage")
                .because("the library opens a file read-write by default and writes it back; the upload reads from "
                        + "bytes only and the owner's file is never opened (ADR-0253)").allowEmptyShould(true);
    }

    @Test
    void onlyTheSheetsModuleNamesTheLibrary() {
        libraryOnlyInTheSheetsModule().check(platform);
    }

    @Test
    void aClassOutsideTheSheetsModuleThatNamesTheLibraryIsCaught() {
        JavaClasses fixture = new ClassFileImporter().importClasses(PoiOutsideTheSheetsModule.class);

        assertThatThrownBy(() -> libraryOnlyInTheSheetsModule().check(fixture))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void noClassOfTheScratchpadModuleIsNamedAnywhere() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(SCRATCHPAD)
                .because("the scratchpad module reads old binary formats and metafiles; it is never added "
                        + "(ADR-0253)").allowEmptyShould(true).check(platform);
    }

    @Test
    void noClassOfTheScratchpadModuleIsOnTheClassPath() {
        for (String name : List.of("org.apache.poi.hwpf.HWPFDocument", "org.apache.poi.hslf.usermodel.HSLFSlideShow",
                "org.apache.poi.hsmf.MAPIMessage", "org.apache.poi.hdgf.HDGFDiagram",
                        "org.apache.poi.hpbf.HPBFDocument")) {
            assertThatThrownBy(() -> Class.forName(name, false, getClass().getClassLoader()))
                    .as(name).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void noClassOfTheSheetsModuleOpensAFileByNameOrWritesOne() {
        noFileOpenedByName().check(platform);
    }
}
