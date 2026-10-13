package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platformapi.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a file may be called and be (ADR-0120): the name, the type, the first bytes. */
class FilePolicyTest {

    private static final FileProperties SETTINGS = new FileProperties(26_214_400L, 20,
            List.of("application/x-msdownload", "text/html", "image/svg+xml"), List.of("exe", "html", "svg", "js"),
            List.of(), Duration.ofHours(1), new FileProperties.Cleanup(true, Duration.ofMinutes(1), 200));
    private final FilePolicy policy = new FilePolicy(SETTINGS);

    @Test
    void aPlainNameAndTypeAreKeptAndTheTypeIsLowerCaseWithoutParameters() {
        FilePolicy.Checked checked = policy.check("Quarterly report.pdf", "Application/PDF; charset=binary");

        assertThat(checked.name()).isEqualTo("Quarterly report.pdf");
        assertThat(checked.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void onlyTheLastPartOfAPathIsKeptWhateverTheSeparator() {
        assertThat(FilePolicy.cleanName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FilePolicy.cleanName("C:\\Users\\someone\\contract.docx")).isEqualTo("contract.docx");
    }

    @Test
    void controlCharactersAndCharactersThatBreakAHeaderOrAFileSystemAreReplaced() {
        String cleaned = FilePolicy.cleanName("na\r\nme\u0000:*?\"<>|.txt");

        assertThat(cleaned).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0000").doesNotContain("\"")
                .doesNotContain("<").doesNotContain("|").endsWith(".txt");
    }

    @Test
    void aLeadingDotIsDroppedSoTheNameIsNeverHiddenOrRelative() {
        assertThat(FilePolicy.cleanName("...hidden")).isEqualTo("hidden");
    }

    @Test
    void aNameThatIsNothingIsRefused() {
        assertThatThrownBy(() -> FilePolicy.cleanName("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FilePolicy.cleanName("///")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FilePolicy.cleanName(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void aLongNameIsCutButKeepsItsExtension() {
        String cleaned = FilePolicy.cleanName("a".repeat(400) + ".pdf");

        assertThat(cleaned).hasSize(150).endsWith(".pdf");
    }

    @Test
    void aDeniedExtensionIsRefusedWhateverTheDeclaredType() {
        assertThatThrownBy(() -> policy.check("setup.EXE", "application/pdf")).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("setup");
        assertThatThrownBy(() -> policy.check("page.html", "text/plain")).isInstanceOf(ApiException.class);
    }

    @Test
    void aDeniedTypeIsRefusedWhateverTheName() {
        assertThatThrownBy(() -> policy.check("notes.txt", "text/html")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.check("notes.txt", "IMAGE/SVG+XML;x=1")).isInstanceOf(ApiException.class);
    }

    @Test
    void aTypeThatHasNotTheShapeOfATypeBecomesTheDefaultTypeAndNeverAnError() {
        assertThat(FilePolicy.normalizeType("not a type")).isEqualTo("application/octet-stream");
        assertThat(FilePolicy.normalizeType("a/b/c")).isEqualTo("application/octet-stream");
        assertThat(FilePolicy.normalizeType(null)).isEqualTo("application/octet-stream");
        assertThat(FilePolicy.normalizeType("")).isEqualTo("application/octet-stream");
    }

    @Test
    void whenTheDeploymentNamesAnAllowedListOnlyThoseTypesPass() {
        FilePolicy strict = new FilePolicy(new FileProperties(1024L, 5, List.of(), List.of(),
                List.of("application/pdf"), Duration.ofHours(1),
                new FileProperties.Cleanup(true, Duration.ofMinutes(1), 10)));

        assertThat(strict.check("a.pdf", "application/pdf").contentType()).isEqualTo("application/pdf");
        assertThatThrownBy(() -> strict.check("a.png", "image/png")).isInstanceOf(ApiException.class);
    }

    @Test
    void theExtensionIsTheLastPartAfterTheLastDot() {
        assertThat(FilePolicy.extensionOf("archive.tar.GZ")).isEqualTo("gz");
        assertThat(FilePolicy.extensionOf("noextension")).isEmpty();
        assertThat(FilePolicy.extensionOf("endswithdot.")).isEmpty();
    }

    @Test
    void aProgramAScriptOrAPageABrowserWouldRunIsRefusedByItsFirstBytes() {
        byte[] windows = {'M', 'Z', (byte) 0x90, 0x00};
        byte[] linked = {0x7F, 'E', 'L', 'F'};
        byte[] script = "#!/bin/sh\necho".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] machO = {(byte) 0xCF, (byte) 0xFA, (byte) 0xED, (byte) 0xFE};
        byte[] javaClass = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        byte[] page = "  <!DOCTYPE html><html>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] script2 = "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] svg = "<svg onload=alert(1)>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        for (byte[] head : List.of(windows, linked, script, machO, javaClass, page, script2, svg)) {
            assertThatThrownBy(() -> FilePolicy.refuseDangerousStart(head, head.length))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void ordinaryContentPassesTheCheckOfTheFirstBytes() {
        byte[] pdf = "%PDF-1.7 ...".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] zip = {'P', 'K', 3, 4};
        byte[] text = "name,email\nuser-a,user-a@example.test\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        for (byte[] head : List.of(pdf, zip, text, png, new byte[0])) {
            FilePolicy.refuseDangerousStart(head, head.length);
        }
    }
}
