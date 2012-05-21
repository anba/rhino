package org.mozilla.javascript.tests;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;
import static org.junit.matchers.JUnitMatchers.hasItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.model.MultipleFailureException;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ErrorReporter;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.TopLevel;

/**
 * Test suite for the Mozilla test suite (jstests). The system property
 * <tt>mozilla.js.tests</tt> must point to the Mozilla jstests directory.
 * 
 * <pre>
 * -ea
 * -Dmozilla.js.tests="/mozilla-central/js/src/tests"
 * -Dmozilla.js.tests.timeout=10000
 * -Dmozilla.js.tests.runslow=false
 * -Duser.language=en -Duser.country=US -Duser.timezone=America/Los_Angeles
 * </pre>
 * 
 * @author André Bargull
 */
@RunWith(LabelledParameterized.class)
public class MozillaSuiteTest2 {

    /** Optimization levels */
    private enum OptLevel {
        Interpreted(-1), Compiled(0), OptCompiled(9);

        private final int level;

        private OptLevel(int level) {
            this.level = level;
        }

        int level() {
            return level;
        }

        static OptLevel forLevel(int level) {
            for (OptLevel o : values()) {
                if (o.level == level) {
                    return o;
                }
            }
            return null;
        }
    }

    /** Context features */
    private enum Feature {
        Strict("strict"), WError("werror"), Xml("xml");

        private final String name;

        private Feature(String name) {
            this.name = name;
        }

        static Feature forName(String name) {
            for (Feature o : values()) {
                if (o.name.equals(name)) {
                    return o;
                }
            }
            return null;
        }
    }

    /**
     * Simple class to store information for each test case
     */
    private static class MozTest {
        boolean enable = true;
        boolean expect = true;
        boolean random = false;
        boolean slow = false;
        String script = null;
        String dir = null;
        Set<OptLevel> opts = EnumSet.allOf(OptLevel.class);

        final String path() {
            return join(dir, "/", script);
        }

        @Override
        public String toString() {
            return path();
        }
    }

    /**
     * Helper class to read "jstests.list" manifest files and manifest entries
     * from js-files
     * 
     * @see <a
     *      href="http://mxr.mozilla.org/mozilla-central/source/layout/tools/reftest/README.txt">Manifest
     *      Format</a>
     */
    private static class Manifest {

        /**
         * Splits a string at every whitespace character and removes empty parts
         */
        private static String[] splitLine(String line, String comment) {
            final String ws = "[ \t\n\r\f\013]";

            // remove comment if any
            int k = line.indexOf(comment);
            if (k != -1) {
                line = line.substring(0, k);
            }

            // split at whitespace and remove empty parts
            String[] parts = line.split(ws);
            int j = 0, len = parts.length;
            for (String p : parts) {
                if (p.length() != 0) {
                    parts[j++] = p;
                }
            }

            if (j == len) {
                return parts;
            }
            String[] tmp = new String[j];
            System.arraycopy(parts, 0, tmp, 0, j);
            return tmp;
        }

        private static final Pattern linePattern;
        static {
            String includeOrUrl = "(?:include\\s+\\S+)|(?:url-prefix\\s+\\S+)";
            // script modificators
            String mod1 = "fails|skip|random|slow|silentfail";
            String mod2 = "(?:(?:fails-if|asserts-if|skip-if|random-if|require-or)\\(\\S+\\))";
            String opt = "(?:-?\\d)";
            String mod3 = "(?:skip-opt\\(" + opt + "(?:," + opt + ")*\\))";
            String mod = "(?:" + mod1 + "|" + mod2 + "|" + mod3 + ")";
            String script1 = "(?:" + mod + "\\s+)*";
            String script2 = "(?:" + script1 + mod + "?)?";
            String script = script1 + "(?:script\\s+\\S+)" + script2;
            // one of: include, url-prefix, script
            String content = "(?:" + includeOrUrl + "|" + script + ")?";
            String comment = "(?:#.*)?";
            String line = "\\s*" + content + "\\s*" + comment;
            linePattern = Pattern.compile(line);
        }

        private static final Pattern jslinePattern;
        static {
            // script modificators
            String mod1 = "fails|skip|random|slow|silentfail";
            String mod2 = "(?:(?:fails-if|asserts-if|skip-if|random-if|require-or)\\(\\S+\\))";
            String opt = "(?:-?\\d)";
            String mod3 = "(?:skip-opt\\(" + opt + "(?:," + opt + ")*\\))";
            String mod = "(?:" + mod1 + "|" + mod2 + "|" + mod3 + ")";
            String script = "(?:(?:" + mod + "\\s+)*" + mod + "?)?";
            // format: tag content comment
            String tag = "\\|.*?\\|";
            String content = "(?:" + script + ")?";
            String comment = "(?:--.*)?";
            String line = "//\\s*" + tag + "\\s*" + content + "\\s*" + comment;
            jslinePattern = Pattern.compile(line);
        }

        private static final Pattern optPattern;
        static {
            optPattern = Pattern.compile("-?\\d");
        }

        private static void parseParts(MozTest test, String[] parts) {
            for (int pos = 0; pos < parts.length;) {
                String p = parts[pos];
                if (p.equals("script")) {
                    assert parts.length > pos;
                    test.script = parts[pos + 1];
                    pos += 2;
                } else {
                    // the other options consume only one token
                    pos += 1;
                    if (p.equals("fails")) {
                        test.expect = false;
                    } else if (p.equals("skip")) {
                        test.expect = test.enable = false;
                    } else if (p.equals("random")) {
                        test.random = true;
                    } else if (p.equals("slow")) {
                        test.slow = true;
                    } else if (p.startsWith("skip-opt")) {
                        // rhino-extension to skip specified opt-levels
                        Matcher m = optPattern.matcher(p);
                        while (m.find()) {
                            int level = Integer.parseInt(m.group());
                            OptLevel opt = OptLevel.forLevel(level);
                            test.opts.remove(opt);
                        }
                    } else if (p.startsWith("fails-if")
                            || p.startsWith("asserts-if")
                            || p.startsWith("skip-if")
                            || p.startsWith("random-if")
                            || p.startsWith("require-or")
                            || p.equals("silentfail")) {
                        // ignored for now...
                    } else if (p.equals("|reftest|")) {
                        // ignore tag
                    } else {
                        System.err.printf("invalid manifest line: %s\n", p);
                    }
                }
            }
        }

        private static void parseManifestFile(List<MozTest> tests,
                File manifest, String reldir) throws IOException {
            BufferedReader reader = newBufferedReaderUTF8(manifest);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    assert linePattern.matcher(line).matches() : line;
                    String[] parts = splitLine(line, "#");
                    if (parts.length == 0) {
                        // ignore empty lines
                    } else if ("include".equals(parts[0])) {
                        assert parts.length > 1;
                        String includeFile = parts[1];
                        String includeDir = new File(includeFile).getParent()
                                .replace(File.separatorChar, '/');
                        File f = new File(manifest.getParentFile(), includeFile);
                        parseManifestFile(tests, f,
                                join(reldir, "/", includeDir));
                    } else if ("url-prefix".equals(parts[0])) {
                        // does not apply to shell tests
                    } else {
                        MozTest test = new MozTest();
                        test.dir = reldir;
                        parseParts(test, parts);
                        assert test.script != null;
                        // negative tests end with "-n"
                        if (test.script.endsWith("-n.js")) {
                            test.expect = false;
                        }
                        tests.add(test);
                    }
                }
            } finally {
                reader.close();
            }
        }

        // Any file who's basename matches something in this set is ignored
        private static final Set<String> excludedSet = new HashSet<String>(
                asList("browser.js", "shell.js", "jsref.js", "template.js",
                        "user.js", "js-test-driver-begin.js",
                        "js-test-driver-end.js"));

        private static void loadFiles(List<MozTest> tests, File dir,
                String reldir) throws IOException {
            List<File> dirs = new ArrayList<File>();
            File[] files = dir.listFiles();
            for (File file : files) {
                if (file.isDirectory()) {
                    dirs.add(file);
                } else if (file.isFile() && file.length() != 0L) {
                    String name = file.getName();
                    if (!excludedSet.contains(name) && name.endsWith(".js")) {
                        MozTest test = new MozTest();
                        test.dir = reldir;
                        test.script = name;
                        // negative tests end with "-n"
                        if (test.script.endsWith("-n.js")) {
                            test.expect = false;
                        }
                        BufferedReader reader = newBufferedReaderUTF8(file);
                        try {
                            String line = reader.readLine();
                            if (jslinePattern.matcher(line).matches()) {
                                String[] parts = splitLine(line.substring(2),
                                        "--");
                                parseParts(test, parts);
                            }
                        } finally {
                            reader.close();
                        }
                        tests.add(test);
                    }
                }
            }
            for (File d : dirs) {
                loadFiles(tests, d, join(reldir, "/", d.getName()));
            }
        }

        /**
         * Recursively reads a "jstests.list" manifest file and returns its
         * content
         */
        static List<MozTest> parse(File manifest) throws IOException {
            List<MozTest> tests = new ArrayList<MozTest>();
            parseManifestFile(tests, manifest, "");
            return tests;
        }

        /**
         * Recursively searches for js-file test cases in {@code testDir} and
         * its sub-directories
         */
        static List<MozTest> load(File testDir) throws IOException {
            List<MozTest> tests = new ArrayList<MozTest>();
            loadFiles(tests, testDir, "");
            return tests;
        }
    }

    /**
     * Returns {@code s1 + sep + s2} if {@code s1.length != 0} otherwise returns
     * {@code s2}
     */
    private static String join(String s1, String sep, String s2) {
        return s1.length() != 0 ? s1 + sep + s2 : s2;
    }

    /**
     * Returns a {@link BufferedReader} for {@code file} with UTF-8 encoding
     */
    private static BufferedReader newBufferedReaderUTF8(File file)
            throws FileNotFoundException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(
                file), Charset.forName("UTF-8")));
    }

    /**
     * Returns a {@link File} which points to the test directory
     * 'mozilla.js.tests'
     */
    private static File testDir() {
        String testPath = System.getProperty("mozilla.js.tests");
        return (testPath != null ? new File(testPath) : null);
    }

    /**
     * Filter the initially collected test cases from 'mozilla.js.tests' and
     * apply the Rhino specific changes
     */
    private static List<MozTest> filterTests(List<MozTest> tests)
            throws IOException {
        File file = new File("testsrc", "jstests.list");
        if (!file.exists()) {
            return tests;
        }
        // list->map
        Map<String, MozTest> map = new HashMap<String, MozTest>();
        for (MozTest test : tests) {
            map.put(test.path(), test);
        }
        // customize tests for Rhino execution
        for (MozTest test : Manifest.parse(file)) {
            MozTest t = map.get(test.path());
            if (t == null) {
                System.err.printf("detected stale entry '%s'\n", test.path());
                continue;
            }
            t.enable &= test.enable;
            t.expect &= test.expect;
            t.slow |= test.slow;
            t.opts = test.opts;
        }
        return tests;
    }

    /**
     * {@link Parameterized} expects a list of {@code Object[]}
     */
    private static Iterable<Object[]> toObjectArray(Iterable<MozTest> iterable) {
        List<Object[]> list = new ArrayList<Object[]>();
        for (MozTest o : iterable) {
            list.add(new Object[] { o });
        }
        return list;
    }

    @Parameters
    public static Iterable<Object[]> mozillaSuiteValues() throws IOException {
        // File manifest = new File(testDir(), "jstests.list");
        // List<MozTest> tests = Manifest.parse(manifest);
        List<MozTest> tests = Manifest.load(testDir());
        return toObjectArray(filterTests(tests));
    }

    @BeforeClass
    public static void setUpClass() {
        File dir = testDir();
        // print cause to System.err because JUnit does (yet) allow to provide
        // messages for failed assumptions
        if (dir == null) {
            System.err.println("missing system property 'mozilla.js.tests'!");
        } else if (!(dir.exists() && dir.isDirectory())) {
            System.err.println("directy 'mozilla.js.tests' does not exist!");
        }
        assumeNotNull(dir);
        assumeTrue(dir.exists());
    }

    /**
     * Returns whether or not to run tests marked as 'slow'
     */
    private static boolean runSlow() {
        return Boolean.getBoolean("mozilla.js.tests.runslow");
    }

    /**
     * Returns the timeout in milli-seconds for each test
     */
    private static int timeout() {
        String timeout = System.getProperty("mozilla.js.tests.timeout");
        if (timeout != null) {
            return Integer.parseInt(timeout);
        }
        // if not specified default to 10s
        return (int) TimeUnit.SECONDS.toMillis(10);
    }

    @Rule
    public Timeout maxTime = new Timeout(timeout());

    private final MozTest moztest;

    public MozillaSuiteTest2(MozTest moztest) {
        this.moztest = moztest;
    }

    @Test
    public void runMozillaTest_Interpreted() throws Throwable {
        runMozillaTest(moztest, OptLevel.Interpreted);
    }

    @Test
    public void runMozillaTest_Compiled() throws Throwable {
        runMozillaTest(moztest, OptLevel.Compiled);
    }

    @Test
    public void runMozillaTest_OptCompiled() throws Throwable {
        runMozillaTest(moztest, OptLevel.OptCompiled);
    }

    /**
     * {@link ContextFactory} implementation which creates {@link TestContext}
     */
    private static class TestContextFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            return new TestContext(this);
        }
    }

    /**
     * Subclass of {@link Context} which provides additional methods to toggle
     * {@link Feature}s at runtime on or off
     */
    private static class TestContext extends Context {
        // e4x is enabled by default
        private final EnumSet<Feature> features = EnumSet.of(Feature.Xml);

        protected TestContext(TestContextFactory factory) {
            super(factory);
        }

        @Override
        public boolean hasFeature(int featureIndex) {
            switch (featureIndex) {
            case FEATURE_E4X:
                return features.contains(Feature.Xml);
            case FEATURE_STRICT_EVAL:
            case FEATURE_STRICT_MODE:
            case FEATURE_STRICT_VARS:
                return features.contains(Feature.Strict);
            case FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER:
                return !features.contains(Feature.Strict);
            case FEATURE_WARNING_AS_ERROR:
                return features.contains(Feature.WError);
            }
            return super.hasFeature(featureIndex);
        }

        /**
         * Returns the currently enabled {@link Feature}s as a string
         */
        String featuresAsString() {
            String s = "";
            for (Feature f : features) {
                s += f.name + ",";
            }
            return s.length() != 0 ? s.substring(0, s.length() - 1) : s;
        }

        /**
         * Enables resp. disables the specified {@link Feature}
         */
        void toggleFeature(Feature feature) {
            if (feature == Feature.Xml) {
                // ignore attempts to disable e4x...
                return;
            }
            if (features.contains(feature)) {
                features.remove(feature);
            } else {
                features.add(feature);
            }
        }
    }

    /**
     * Global object class for the Mozilla test-suite, also provides
     * implementation for some shell-functions which are called in the
     * test-suite.
     * 
     * @see <a
     *      href="https://developer.mozilla.org/en/SpiderMonkey/Shell_global_objects">Shell
     *      Global Objects</a>
     */
    @SuppressWarnings({ "serial", "unused" })
    private static class Global extends TopLevel {
        private static final boolean PRINT_MESSAGES = false;
        private List<Throwable> failures = new ArrayList<Throwable>();

        List<Throwable> getFailures() {
            return failures;
        }

        private final TestContext currentContext() {
            return (TestContext) Context.getCurrentContext();
        }

        final void defineFunctions(String... names) {
            defineFunctionProperties(names, Global.class,
                    ScriptableObject.DONTENUM);
        }

        /** testsuite-function: {@code reportFailure(msg)} */
        public void reportFailure(Object msg) {
            // collect all failures instead of calling fail() directly
            failures.add(new AssertionError(Context.toString(msg)));
        }

        /** shell-function: {@code print([exp, ...])} */
        public static void print(Context cx, Scriptable thisObj, Object[] args,
                Function funObj) {
            if (PRINT_MESSAGES) {
                PrintStream out = System.out;
                for (int i = 0, len = args.length; i < len; ++i) {
                    if (i > 0) {
                        out.print(' ');
                    }
                    out.print(Context.toString(args[i]));
                }
                out.println();
            }
        }

        /** shell-function: {@code load(path)} */
        public void load(String path) {
            File file = new File(testDir(), path);
            if (!file.exists()) {
                System.err.printf("file '%s' not found!\n", path);
            } else {
                evalOrFail(file, currentContext(), this);
            }
        }

        /** shell-function: {@code gc()} */
        public static void gc() {
            // gc() needs to be static!
            System.gc();
        }

        /** shell-function: {@code options([name])} */
        public String options(Object name) {
            TestContext tcx = currentContext();
            String p = tcx.featuresAsString();
            if (name != Context.getUndefinedValue()) {
                String n = Context.toString(name);
                Feature feature = Feature.forName(n);
                if (feature == null) {
                    fail(String.format("Feature '%s' is not supported!", n));
                }
                tcx.toggleFeature(feature);
            }
            return p;
        }

        /** shell-function: {@code version([number])} */
        public String version(Object version) {
            Context cx = currentContext();
            int p = cx.getLanguageVersion();
            int v = ScriptRuntime.toInt32(version);
            if (Context.isValidLanguageVersion(v)) {
                cx.setLanguageVersion(v);
            } else if (v == 181 || v == 185) {
                // map 1.8.1 and 1.8.5 to 1.8 for now
                cx.setLanguageVersion(Context.VERSION_1_8);
            }
            return ScriptRuntime.toString(p);
        }
    }

    @SuppressWarnings("serial")
    private static class StopEvaluationException extends EvaluatorException {
        public StopEvaluationException(String detail, String sourceName,
                int lineNumber, String lineSource, int columnNumber) {
            super(detail, sourceName, lineNumber, lineSource, columnNumber);
        }
    }

    /**
     * {@link ErrorReporter} implementation which collects any errors
     */
    private static class CollectingErrorReporter implements ErrorReporter {
        private List<Throwable> errors = new ArrayList<Throwable>();

        List<? extends Throwable> getErrors() {
            return errors;
        }

        @Override
        public void warning(String message, String sourceName, int line,
                String lineSource, int lineOffset) {
            // ignore warnings for now
        }

        @Override
        public void error(String message, String sourceName, int line,
                String lineSource, int lineOffset) {
            EvaluatorException e = new EvaluatorException(message, sourceName,
                    line, lineSource, lineOffset);
            errors.add(new AssertionError(e.getMessage(), e));
        }

        @Override
        public EvaluatorException runtimeError(String message,
                String sourceName, int line, String lineSource, int lineOffset) {
            StopEvaluationException e = new StopEvaluationException(message,
                    sourceName, line, lineSource, lineOffset);
            errors.add(new AssertionError(e.getMessage(), e));
            return e;
        }
    }

    private void runMozillaTest(MozTest moztest, OptLevel opt) throws Throwable {
        // ensure opt-level is enabled for this test
        assumeThat(moztest.opts, hasItem(opt));
        // filter disabled testes
        assumeTrue(moztest.enable);
        // don't run slow tests unless explicitly requested
        assumeTrue(!moztest.slow || runSlow());

        CollectingErrorReporter reporter = new CollectingErrorReporter();
        TestContextFactory factory = new TestContextFactory();
        Context cx = factory.enterContext();
        cx.setOptimizationLevel(opt.level());
        cx.setErrorReporter(reporter);
        try {
            Global global = new Global();
            // start initialization
            cx.initStandardObjects(global);
            // export these functions early
            global.defineFunctions("print", "load", "gc", "options", "version");
            // load and execute shell.js files
            for (File shell : shellJS(moztest)) {
                compile(shell, opt, cx).exec(cx, global);
            }
            // export functions to overwrite shell.js defaults
            global.defineFunctions("reportFailure");

            // evaluate actual test-script
            File js = new File(testDir(), moztest.path());
            evalOrFail(js, cx, global);

            // fail if any test returns with errors
            List<Throwable> failures = new ArrayList<Throwable>();
            failures.addAll(global.getFailures());
            failures.addAll(reporter.getErrors());
            if (moztest.random) {
                // random tests are to be ignored...
            } else if (moztest.expect) {
                MultipleFailureException.assertEmpty(failures);
            } else {
                assertFalse("Expected test to throw error", failures.isEmpty());
            }
        } finally {
            Context.exit();
        }
    }

    /**
     * Returns an {@link Iterable} of 'shell.js'-{@link File}s
     */
    private static Iterable<File> shellJS(MozTest test) {
        final String shellJS = "shell.js";

        // add 'shell.js' files from each directory (if present)
        List<File> files = new ArrayList<File>();
        File dir = testDir();
        for (String s : test.path().split("/")) {
            File f = new File(dir, shellJS);
            if (f.exists()) {
                files.add(f);
            }
            dir = new File(dir, s);
        }

        return files;
    }

    /**
     * Simple cache for the compiled 'shell.js' files
     */
    @SuppressWarnings("serial")
    private static Map<String, Script> scriptCache = new LinkedHashMap<String, Script>(
            16, .75f, true) {
        private final int MAX_DEPTH = 5;
        private final int MAX_SIZE = OptLevel.values().length * MAX_DEPTH;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Script> eldest) {
            return (size() > MAX_SIZE);
        }
    };

    /**
     * Compiles {@code file} to a {@link Script} and caches the result in
     * {@code MozillaSuiteTest2#scriptCache}
     */
    private static Script compile(File file, OptLevel opt, Context cx) {
        String name = file.getAbsolutePath();
        String key = name + "{opt=" + opt + "}";
        Script script = scriptCache.get(key);
        if (script == null) {
            try {
                BufferedReader reader = newBufferedReaderUTF8(file);
                try {
                    script = cx.compileReader(reader, file.getName(), 1, null);
                } finally {
                    reader.close();
                }
            } catch (IOException e) {
                fail(e.getMessage());
            }

            scriptCache.put(key, script);
        }
        return script;
    }

    /**
     * Evaluates {@code file} and collects runtime errors (if any)
     */
    private static void evalOrFail(File file, Context cx, Global global) {
        try {
            BufferedReader reader = newBufferedReaderUTF8(file);
            try {
                cx.evaluateReader(global, reader, file.getName(), 1, null);
            } catch (EcmaError e) {
                // count towards the overall failure count
                global.failures.add(new AssertionError(e.getMessage(), e));
            } catch (JavaScriptException e) {
                // count towards the overall failure count
                global.failures.add(new AssertionError(e.getMessage(), e));
            } catch (StopEvaluationException e) {
                // ignore
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            fail(e.getMessage());
        }
    }
}
