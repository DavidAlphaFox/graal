package com.oracle.truffle.api.bytecode.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeParser;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNodes;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.GenerateBytecodeTestVariants;
import com.oracle.truffle.api.bytecode.GenerateBytecodeTestVariants.Variant;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;

@RunWith(Parameterized.class)
public class StackTraceTest extends AbstractInstructionTest {

    protected static final BytecodeDSLTestLanguage LANGUAGE = null;

    enum Interpreter {

        CACHED_DEFAULT(StackTraceTestRootNodeCachedDefault.class, true),
        UNCACHED_DEFAULT(StackTraceTestRootNodeUncachedDefault.class, false),
        CACHED_BCI_IN_FRAME(StackTraceTestRootNodeCachedBciInFrame.class, true),
        UNCACHED_BCI_IN_FRAME(StackTraceTestRootNodeUncachedBciInFrame.class, false);

        final Class<? extends StackTraceTestRootNode> clazz;
        final boolean cached;

        Interpreter(Class<? extends StackTraceTestRootNode> clazz, boolean cached) {
            this.clazz = clazz;
            this.cached = cached;
        }
    }

    static final int[] DEPTHS = new int[]{1, 2, 3, 4, 8, 13, 16, 100};
    static final int REPEATS = 4;

    record Run(Interpreter interpreter, int depth) {
        @Override
        public String toString() {
            return interpreter.clazz.getSimpleName() + "(depth=" + depth + ")";
        }
    }

    @Parameters(name = "{0}")
    public static List<Run> createRuns() {
        List<Run> runs = new ArrayList<>(Interpreter.values().length * DEPTHS.length);
        for (Interpreter interpreter : Interpreter.values()) {
            for (int depth : DEPTHS) {
                runs.add(new Run(interpreter, depth));
            }
        }
        return runs;
    }

    @Parameter(0) public Run run;

    @Test
    public void testThrow() {
        int depth = run.depth;
        StackTraceTestRootNode[] nodes = chainCalls(depth, b -> {
            b.beginRoot(LANGUAGE);
            b.emitDummy();
            b.emitThrowError();
            b.endRoot();
        }, true);
        StackTraceTestRootNode outer = nodes[nodes.length - 1];

        for (int repeat = 0; repeat < REPEATS; repeat++) {
            try {
                outer.getCallTarget().call();
                Assert.fail();
            } catch (TestException e) {
                List<TruffleStackTraceElement> elements = TruffleStackTrace.getStackTrace(e);
                assertEquals(nodes.length, elements.size());
                for (int i = 0; i < nodes.length; i++) {
                    assertStackElement(elements.get(i), nodes[i]);
                }
            }
        }
    }

    @Test
    public void testThrowBehindInterop() {
        int depth = run.depth;
        StackTraceTestRootNode[] nodes = chainCalls(depth, b -> {
            b.beginRoot(LANGUAGE);
            b.beginThrowErrorBehindInterop();
            b.emitLoadConstant(new ThrowErrorExecutable());
            b.endThrowErrorBehindInterop();
            b.endRoot();
        }, true);
        StackTraceTestRootNode outer = nodes[nodes.length - 1];

        for (int repeat = 0; repeat < REPEATS; repeat++) {
            try {
                outer.getCallTarget().call();
                Assert.fail();
            } catch (TestException e) {
                List<TruffleStackTraceElement> elements = TruffleStackTrace.getStackTrace(e);
                assertEquals(nodes.length, elements.size());
                for (int i = 0; i < nodes.length; i++) {
                    assertStackElement(elements.get(i), nodes[i]);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCapture() {
        int depth = run.depth;
        StackTraceTestRootNode[] nodes = chainCalls(depth, b -> {
            b.beginRoot(LANGUAGE);
            b.emitDummy();
            b.beginReturn();
            b.emitCaptureStack();
            b.endReturn();
            b.endRoot();
        }, true);
        StackTraceTestRootNode outer = nodes[nodes.length - 1];

        for (int repeat = 0; repeat < REPEATS; repeat++) {
            List<TruffleStackTraceElement> elements = (List<TruffleStackTraceElement>) outer.getCallTarget().call();
            assertEquals(nodes.length, elements.size());
            for (int i = 0; i < nodes.length; i++) {
                assertStackElement(elements.get(i), nodes[i]);
            }
        }
    }

    private void assertStackElement(TruffleStackTraceElement element, StackTraceTestRootNode target) {
        assertSame(target.getCallTarget(), element.getTarget());
        assertNotNull(element.getLocation());
        BytecodeNode bytecode = target.getBytecodeNode();
        if (run.interpreter.cached) {
            assertSame(bytecode, BytecodeNode.get(element.getLocation()));
        } else {
            assertSame(bytecode, element.getLocation());
        }
        assertEquals(bytecode.getInstructionsAsList().get(1).getBytecodeIndex(), element.getBytecodeIndex());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNoLocation() {
        int depth = run.depth;
        StackTraceTestRootNode[] nodes = chainCalls(depth, b -> {
            b.beginRoot(LANGUAGE);
            b.emitDummy();
            b.emitThrowErrorNoLocation();
            b.endRoot();
        }, false);
        StackTraceTestRootNode outer = nodes[nodes.length - 1];
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            try {
                outer.getCallTarget().call();
                Assert.fail();
            } catch (TestException e) {
                List<TruffleStackTraceElement> elements = TruffleStackTrace.getStackTrace(e);
                assertEquals(nodes.length, elements.size());
                for (int i = 0; i < nodes.length; i++) {
                    assertStackElementNoLocation(elements.get(i), nodes[i]);
                }
            }
        }
    }

    private static void assertStackElementNoLocation(TruffleStackTraceElement element, StackTraceTestRootNode target) {
        assertSame(target.getCallTarget(), element.getTarget());
        assertNull(element.getLocation());
        assertEquals(-1, element.getBytecodeIndex());
    }

    private StackTraceTestRootNode[] chainCalls(int depth, BytecodeParser<StackTraceTestRootNodeBuilder> innerParser, boolean includeLocation) {
        StackTraceTestRootNode[] nodes = new StackTraceTestRootNode[depth];
        nodes[0] = parse(innerParser);
        for (int i = 1; i < depth; i++) {
            int index = i;
            nodes[i] = parse(b -> {
                b.beginRoot(LANGUAGE);
                b.emitDummy();
                b.beginReturn();
                CallTarget target = nodes[index - 1].getCallTarget();
                if (includeLocation) {
                    b.emitCall(target);
                } else {
                    b.emitCallNoLocation(target);
                }
                b.endReturn();
                b.endRoot().depth = index;
            });
        }
        return nodes;
    }

    @GenerateBytecodeTestVariants({
                    @Variant(suffix = "CachedDefault", configuration = //
                    @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, storeBytecodeIndexInFrame = false, enableUncachedInterpreter = false, boxingEliminationTypes = {int.class})),
                    @Variant(suffix = "UncachedDefault", configuration = //
                    @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, storeBytecodeIndexInFrame = false, enableUncachedInterpreter = true, boxingEliminationTypes = {int.class})),
                    @Variant(suffix = "CachedBciInFrame", configuration =//
                    @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, storeBytecodeIndexInFrame = true, enableUncachedInterpreter = false, boxingEliminationTypes = {int.class})),
                    @Variant(suffix = "UncachedBciInFrame", configuration =//
                    @GenerateBytecode(languageClass = BytecodeDSLTestLanguage.class, storeBytecodeIndexInFrame = true, enableUncachedInterpreter = true, boxingEliminationTypes = {int.class}))
    })
    public abstract static class StackTraceTestRootNode extends DebugBytecodeRootNode implements BytecodeRootNode {

        protected StackTraceTestRootNode(TruffleLanguage<?> language,
                        FrameDescriptor frameDescriptor) {
            super(language, frameDescriptor);
        }

        public int depth;

        @Override
        public String toString() {
            return "StackTest[depth=" + depth + "]";
        }

        // just used to increment the instruction index
        @Operation
        static final class Dummy {
            @Specialization
            static void doDefault() {
            }
        }

        @Operation
        @ConstantOperand(type = CallTarget.class)
        static final class Call {
            @Specialization
            static Object doDefault(CallTarget target,@Bind Node node) {
                return target.call(node);
            }
        }

        @Operation
        @ConstantOperand(type = CallTarget.class)
        static final class CallNoLocation {
            @Specialization
            static Object doDefault(CallTarget target) {
                return target.call((Node) null);
            }
        }

        @Operation
        static final class ThrowErrorBehindInterop {

            @Specialization(limit = "1")
            static Object doDefault(Object executable, @CachedLibrary("executable") InteropLibrary executables) {
                try {
                    return executables.execute(executable);
                } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere(e);
                }
            }
        }

        @Operation
        static final class ThrowError {

            @Specialization
            static Object doDefault(@Bind Node node) {
                throw new TestException(node);
            }
        }

        @Operation
        static final class ThrowErrorNoLocation {

            @Specialization
            static Object doDefault() {
                throw new TestException(null);
            }
        }

        @Operation
        static final class CaptureStack {

            @Specialization
            static Object doDefault(@Bind Node node) {
                TestException ex = new TestException(node);
                return TruffleStackTrace.getStackTrace(ex);
            }
        }

    }

    @ExportLibrary(InteropLibrary.class)
    @SuppressWarnings("static-method")
    static class ThrowErrorExecutable implements TruffleObject {

        @ExportMessage
        @SuppressWarnings("unused")
        final Object execute(Object[] args, @CachedLibrary("this") InteropLibrary library) {
            throw new TestException(library);
        }

        @ExportMessage
        final boolean isExecutable() {
            return true;
        }

    }

    @SuppressWarnings("serial")
    static class TestException extends AbstractTruffleException {

        TestException(Node location) {
            super(resolveLocation(location));
        }

        private static Node resolveLocation(Node location) {
            if (location == null) {
                return null;
            }
            if (location.isAdoptable()) {
                return location;
            } else {
                return EncapsulatingNodeReference.getCurrent().get();
            }
        }
    }

    private StackTraceTestRootNode parse(BytecodeParser<StackTraceTestRootNodeBuilder> parser) {
        BytecodeRootNodes<StackTraceTestRootNode> nodes = invokeCreate(run.interpreter.clazz, BytecodeConfig.WITH_SOURCE, parser);
        StackTraceTestRootNode root = nodes.getNodes().get(nodes.getNodes().size() - 1);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static <T extends StackTraceTestRootNode> BytecodeRootNodes<T> invokeCreate(Class<? extends StackTraceTestRootNode> interpreterClass, BytecodeConfig config,
                    BytecodeParser<? extends StackTraceTestRootNodeBuilder> builder) {
        try {
            Method create = interpreterClass.getMethod("create", BytecodeConfig.class, BytecodeParser.class);
            return (BytecodeRootNodes<T>) create.invoke(null, config, builder);
        } catch (InvocationTargetException e) {
            // Exceptions thrown by the invoked method can be rethrown as runtime exceptions that
            // get caught by the test harness.
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else if (e.getCause() instanceof Error) {
                throw (Error) e.getCause();
            } else {
                throw new AssertionError(e);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

}
