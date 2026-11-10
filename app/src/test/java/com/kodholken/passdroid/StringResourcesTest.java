package com.kodholken.passdroid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * aapt2 reads a backslash at the end of a line in a resource file as an
 * escaped line break, so the text gets a real line break there. A string may
 * wrap in the source without it: aapt2 turns the line break into a space.
 * Lint has no check for this.
 */
public class StringResourcesTest {
    // Gradle runs the unit tests in the module directory
    private static final File RES = new File("src/main/res");

    @Test
    public void noLineEndsWithABackslash() throws IOException {
        List<File> files = valuesFiles();
        assertFalse("No resource files found in " + RES.getAbsolutePath(),
                    files.isEmpty());

        List<String> found = new ArrayList<String>();
        for (File file : files) {
            List<String> lines = Files.readAllLines(file.toPath(),
                                                    StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (endsWithEscape(lines.get(i))) {
                    found.add(file.getPath() + ":" + (i + 1));
                }
            }
        }

        if (!found.isEmpty()) {
            fail("These lines end with a backslash, which puts a line break "
                 + "into the text. Remove it, and use \\n where a line break "
                 + "is wanted:\n" + String.join("\n", found));
        }
    }

    @Test
    public void escapedLineBreaks() {
        assertTrue(endsWithEscape("first line \\"));
        assertTrue(endsWithEscape("paragraph.\\n\\"));
        assertTrue(endsWithEscape("<string name=\"a\">\\"));
        assertFalse(endsWithEscape("first line"));
        assertFalse(endsWithEscape("a backslash \\\\"));
        assertFalse(endsWithEscape("a line break\\n"));
        assertFalse(endsWithEscape(""));
    }

    /** The .xml files of all res/values* directories. */
    private static List<File> valuesFiles() {
        List<File> files = new ArrayList<File>();
        File[] dirs = RES.listFiles();
        if (dirs == null) {
            return files;
        }
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("values")) {
                continue;
            }
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File file : children) {
                if (file.getName().endsWith(".xml")) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /**
     * Whether the line ends with an escaping backslash. Two backslashes are
     * an escaped backslash, so the count of them decides.
     */
    static boolean endsWithEscape(String line) {
        return countTrailing(line) % 2 == 1;
    }

    private static int countTrailing(String line) {
        int count = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            count++;
        }
        return count;
    }
}
