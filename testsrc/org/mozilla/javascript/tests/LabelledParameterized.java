package org.mozilla.javascript.tests;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Suite;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * JUnit {@link Suite} implementation similar to {@link Parameterized}. In
 * contrast to the latter one, this implementation gives each test a named
 * label.
 * 
 * @author André Bargull
 * 
 */
public class LabelledParameterized extends Suite {

    private static final class LabelledRunner extends BlockJUnit4ClassRunner {
        private final Object[] params;

        LabelledRunner(Class<?> clazz, Object[] params)
                throws InitializationError {
            super(clazz);
            this.params = params;
        }

        @Override
        protected void validateConstructor(List<Throwable> errors) {
            // remove validation for zero-arg ctor from super-class
            validateOnlyOneConstructor(errors);
        }

        @Override
        protected Statement classBlock(RunNotifier notifier) {
            // override super-class method, dispatch only to children
            return childrenInvoker(notifier);
        }

        @Override
        protected Object createTest() throws Exception {
            // invoke the ctor with the supplied parameters for this test
            Constructor<?> ctor = getTestClass().getOnlyConstructor();
            return ctor.newInstance(params);
        }

        private static class RunNotifierAdaptor extends RunNotifier {
            private final RunNotifier notifier;

            public RunNotifierAdaptor(RunNotifier notifier) {
                this.notifier = notifier;
            }

            @Override
            public void addListener(RunListener listener) {
                notifier.addListener(listener);
            }

            @Override
            public void removeListener(RunListener listener) {
                notifier.removeListener(listener);
            }

            @Override
            public void addFirstListener(RunListener listener) {
                notifier.addFirstListener(listener);
            }

            @Override
            public void fireTestStarted(Description description)
                    throws StoppedByUserException {
                notifier.fireTestStarted(description);
            }

            @Override
            public void fireTestFinished(Description description) {
                notifier.fireTestFinished(description);
            }

            @Override
            public void fireTestAssumptionFailed(Failure failure) {
                notifier.fireTestAssumptionFailed(failure);
            }

            @Override
            public void fireTestFailure(Failure failure) {
                notifier.fireTestFailure(failure);
            }

            @Override
            public void fireTestIgnored(Description description) {
                notifier.fireTestIgnored(description);
            }

            @Override
            public void fireTestRunFinished(Result result) {
                notifier.fireTestRunFinished(result);
            }

            @Override
            public void fireTestRunStarted(Description description) {
                notifier.fireTestRunStarted(description);
            }

            @Override
            public void pleaseStop() {
                notifier.pleaseStop();
            }
        }

        @Override
        protected void runChild(final FrameworkMethod method,
                RunNotifier notifier) {
            // display failed assumptions as ignored
            RunNotifier adaptor = new RunNotifierAdaptor(notifier) {
                @Override
                public void fireTestAssumptionFailed(Failure failure) {
                    super.fireTestIgnored(describeChild(method));
                }
            };
            super.runChild(method, adaptor);
        }

        /**
         * Returns the label which is the first parameter by default
         */
        protected String getLabel() {
            return String.valueOf(params[0]);
        }

        @Override
        protected String getName() {
            return getLabel();
        }

        @Override
        protected String testName(FrameworkMethod method) {
            return method.getName() + Arrays.toString(params);
        }
    }

    private List<Runner> children;

    public LabelledParameterized(Class<?> clazz) throws Throwable {
        super(clazz, Collections.<Runner> emptyList());
        children = createChildren();
    }

    @Override
    protected void collectInitializationErrors(List<Throwable> errors) {
        super.collectInitializationErrors(errors);
        validateParametersMethod(errors);
    }

    protected void validateParametersMethod(List<Throwable> errors) {
        final int PUBLIC_STATIC = Modifier.PUBLIC | Modifier.STATIC;
        List<FrameworkMethod> methods = getTestClass().getAnnotatedMethods(
                Parameters.class);
        if (methods.size() != 1) {
            String msg = String.format(
                    "found %d @Parameters methods, but expected 1",
                    methods.size());
            errors.add(new Exception(msg));
            // return early in this case
            return;
        }
        Method m = methods.get(0).getMethod();
        if ((m.getModifiers() & PUBLIC_STATIC) != PUBLIC_STATIC) {
            String msg = String.format(
                    "@Parameters method is '%s', but expected '%s'",
                    Modifier.toString(m.getModifiers()),
                    Modifier.toString(PUBLIC_STATIC));
            errors.add(new Exception(msg));
        }
        if (m.getParameterTypes().length != 0) {
            String msg = String.format(
                    "@Parameters has %d parameters, but expected none",
                    m.getParameterTypes().length);
            errors.add(new Exception(msg));
        }
        Class<?> type = m.getReturnType();
        if (!(type == Object.class || Iterable.class.isAssignableFrom(type))) {
            String msg = String.format(
                    "@Parameters returns '%s', but expected '%s'", type,
                    Iterable.class);
            errors.add(new Exception(msg));
        }
    }

    @Override
    protected List<Runner> getChildren() {
        return children;
    }

    /**
     * Creates the children {@link Runner} for this runner
     */
    protected List<Runner> createChildren() throws Throwable {
        Class<?> clazz = getTestClass().getJavaClass();
        List<Runner> children = new ArrayList<Runner>();
        for (Object[] p : retrieveParams()) {
            children.add(new LabelledRunner(clazz, p));
        }
        return children;
    }

    /**
     * Returns the first method annotated with {@link Parameters}
     */
    protected FrameworkMethod getParametersMethod() {
        return getTestClass().getAnnotatedMethods(Parameters.class).get(0);
    }

    /**
     * Retrieves the test parameters as specified in {@link Parameters}
     */
    protected Iterable<Object[]> retrieveParams() throws Throwable {
        FrameworkMethod fm = getParametersMethod();
        @SuppressWarnings("unchecked")
        Iterable<Object[]> p = (Iterable<Object[]>) fm.invokeExplosively(null);
        return p;
    }
}
