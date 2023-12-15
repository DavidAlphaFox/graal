/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.bytecode;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.introspection.BytecodeIntrospection;
import com.oracle.truffle.api.bytecode.introspection.ExceptionHandler;
import com.oracle.truffle.api.bytecode.introspection.Instruction;
import com.oracle.truffle.api.bytecode.introspection.SourceInformation;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Base interface to be implemented by the root node of a Bytecode DSL interpreter. Such a root node
 * should extend {@link com.oracle.truffle.api.nodes.RootNode} and be annotated with
 * {@link @GenerateBytecode}.
 *
 * @see GenerateBytecode
 */
public interface BytecodeRootNode extends BytecodeIntrospection.Provider {

    /**
     * Entrypoint to the root node.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param frame the frame used for execution
     * @return the value returned by the root node
     */
    Object execute(VirtualFrame frame);

    /**
     * Optional hook invoked before executing the root node.
     *
     * @param frame the frame used for execution
     */
    @SuppressWarnings("unused")
    default void executeProlog(VirtualFrame frame) {
    }

    /**
     * Optional hook invoked before leaving the root node.
     *
     * @param frame the frame used for execution
     * @param returnValue the value returned by the root node ({@code null} if an exception was
     *            thrown)
     * @param throwable the exception thrown by the root node ({@code null} if the root node
     *            returned normally)
     */
    @SuppressWarnings("unused")
    default void executeEpilog(VirtualFrame frame, Object returnValue, Throwable throwable) {
    }

    /**
     * Optional hook invoked when a {@link ControlFlowException} is thrown during execution. This
     * hook can do one of four things:
     *
     * <ol>
     * <li>It can return a value. The value will be returned from the root node (this can be used to
     * implement early returns).
     * <li>It can throw the same or a different {@link ControlFlowException}. The thrown exception
     * will be thrown from the root node.
     * <li>It can throw an {@link AbstractTruffleException}. The thrown exception will be forwarded
     * to the guest code for handling.
     * <li>It can throw an internal error, which will be intercepted by
     * {@link #interceptInternalException}.
     * </ol>
     *
     * @param ex the control flow exception
     * @param frame the frame at the point the exception was thrown
     * @param bci the bytecode index of the instruction that caused the exception
     * @return the Truffle exception to be handled by guest code
     */
    @SuppressWarnings("unused")
    default Object interceptControlFlowException(ControlFlowException ex, VirtualFrame frame, int bci) throws Throwable {
        throw ex;
    }

    /**
     * Optional hook invoked when an internal exception (i.e., anything other than
     * {@link AbstractTruffleException} or {@link ControlFlowException}) is thrown during execution.
     * This hook can be used to convert such exceptions into guest-language exceptions that can be
     * handled by guest code.
     *
     * <p>
     * For example, if a Java {@link StackOverflowError} is thrown, this hook can be used to return
     * a guest-language equivalent exception that the guest code understands.
     *
     * <p>
     * If the return value is an {@link AbstractTruffleException}, it will be forwarded to the guest
     * code for handling. The exception will also be intercepted by
     * {@link #interceptTruffleException}.
     *
     * If the return value is not an {@link AbstractTruffleException}, it will be rethrown. Thus, if
     * an internal error cannot be converted to a guest exception, it can simply be returned.
     *
     * @param t the internal exception
     * @param bci the bytecode index of the instruction that caused the exception
     * @return an equivalent guest-language exception or an exception to be rethrown
     */
    @SuppressWarnings("unused")
    default Throwable interceptInternalException(Throwable t, int bci) {
        return t;
    }

    /**
     * Optional hook invoked when an {@link AbstractTruffleException} is thrown during execution.
     * This hook can be used to preprocess the exception or replace it with another exception before
     * it is handled.
     *
     * @param ex the Truffle exception
     * @param frame the frame at the point the exception was thrown
     * @param bci the bytecode index of the instruction that caused the exception
     * @return the Truffle exception to be handled by guest code
     */
    @SuppressWarnings("unused")
    default AbstractTruffleException interceptTruffleException(AbstractTruffleException ex, VirtualFrame frame, int bci) {
        return ex;
    }

    /**
     * Sets an invocation threshold that must be reached before the
     * {@link GenerateBytecode#enableUncachedInterpreter uncached interpreter} switches to a
     * specializing interpreter. This method has no effect if there is no uncached interpreter or
     * the root node has node has already switched to a specializing interpreter.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param invocationCount the invocation threshold
     */
    @SuppressWarnings("unused")
    default void setUncachedInterpreterThreshold(int invocationCount) {
    }

    /**
     * Gets the {@link SourceSection} associated with a particular {@code bci}. Returns {@code null}
     * if the node was not parsed {@link BytecodeConfig#WITH_SOURCE with sources} or if there is no
     * associated source section for the given {@code bci}.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param bci the bytecode index
     * @return a source section corresponding to the bci, or {@code null} if no source section is
     *         available
     */
    @SuppressWarnings("unused")
    default SourceSection findSourceSectionAtBci(int bci) {
        throw new AbstractMethodError();
    }

    /**
     * Gets the {@code bci} associated with a particular
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance} obtained from a stack walk.
     *
     * @param frameInstance the frame instance
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    static int findBci(FrameInstance frameInstance) {
        /**
         * We use two strategies to communicate the current bci.
         *
         * For cached interpreters, each operation node corresponds to a unique bci. We can walk the
         * parent chain of the call node to find the operation node, and then use it to compute a
         * bci. This incurs no overhead during regular execution.
         *
         * For uncached interpreters, we use uncached nodes, so the call node (if any) is not
         * adopted by an operation node. Instead, the uncached interpreter stores the current bci
         * into the frame before any operation that might call another node. This incurs a bit of
         * overhead during regular execution (but just for the uncached interpreter).
         */
        int fromCallNode = findBciFromLocation(frameInstance.getCallNode());
        if (fromCallNode != -1) {
            return fromCallNode;
        }
        if (frameInstance.getCallTarget() instanceof RootCallTarget rootCallTarget && rootCallTarget.getRootNode() instanceof BytecodeRootNode bytecodeRootNode) {
            return bytecodeRootNode.readBciFromFrame(frameInstance.getFrame(FrameAccess.READ_ONLY));
        }
        return -1;
    }

    private static int findBciFromLocation(Node location) {
        for (Node operationNode = location; operationNode != null; operationNode = operationNode.getParent()) {
            if (operationNode.getParent() instanceof BytecodeRootNode rootNode) {
                return rootNode.findBciOfOperationNode(operationNode);
            }
        }
        return -1;
    }

    /**
     * Gets the {@link SourceSection} associated with a particular {@code location}. Returns
     * {@code null} if the node is not adopted by a {@code BytecodeRootNode}, or if there is no
     * associated bytecode index and source section for the given {@code location}.
     *
     * Note: this is a slow path operation that gets invoked by Truffle internal code. It should not
     * be called directly. Operation specializations can use {@code @Bind("$bci")} to obtain the
     * current bytecode index on the fast path.
     *
     * @param location the node
     * @return a source section corresponding to the node, or {@code null} if no source section is
     *         available
     */
    static SourceSection findSourceSectionFromLocation(Node location) {
        for (Node operationNode = location; operationNode != null; operationNode = operationNode.getParent()) {
            if (operationNode.getParent() instanceof BytecodeRootNode rootNode) {
                int bci = rootNode.findBciOfOperationNode(operationNode);
                return rootNode.findSourceSectionAtBci(bci);
            }
        }
        return null;
    }

    /**
     * Gets the {@code bci} associated with a particular operation node.
     *
     * Note: this is a slow path operation that gets invoked by Truffle internal code. It should not
     * be called directly. Operation specializations can use {@code @Bind("$bci")} to obtain the
     * current bytecode index on the fast path.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param operationNode the operation node
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    @SuppressWarnings("unused")
    default int findBciOfOperationNode(Node operationNode) {
        throw new AbstractMethodError();
    }

    /**
     * Reads the {@code bci} stored in the frame.
     *
     * This method should only be invoked by the language when
     * {@link GenerateBytecode#storeBciInFrame} is {@code true}, because there is otherwise no
     * guarantee that the {@code bci} will be stored in the frame.
     *
     * Note: When possible, it is preferable to obtain the {@code bci} from Operation
     * specializations using {@code @Bind("$bci")} for performance reasons. This method should only
     * be used by the language when the {@code bci} is needed in non-local contexts (e.g., when the
     * frame has escaped to another root node).
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param frame the frame obtained from a stack walk
     * @return the corresponding bytecode index, or -1 if the index could not be found
     */
    @SuppressWarnings("unused")
    default int readBciFromFrame(Frame frame) {
        throw new AbstractMethodError();
    }

    /**
     * Returns a new array containing the current value of each local in the
     * {@link com.oracle.truffle.api.frame.FrameInstance frameInstance}.
     *
     * @see {@link #getLocals(Frame)}
     * @param frameInstance the frame instance
     * @return a new array of local values, or null if the frame instance does not correspond to an
     *         {@link BytecodeRootNode}
     */
    static Object[] getLocals(FrameInstance frameInstance) {
        if (!(frameInstance.getCallTarget() instanceof RootCallTarget rootCallTarget)) {
            return null;
        }
        if (rootCallTarget.getRootNode() instanceof BytecodeRootNode bytecodeRootNode) {
            return bytecodeRootNode.getLocals(frameInstance.getFrame(FrameAccess.READ_ONLY));
        } else if (rootCallTarget.getRootNode() instanceof ContinuationRootNode continuationRootNode) {
            return continuationRootNode.getLocals(frameInstance.getFrame(FrameAccess.READ_ONLY));
        }
        return null;
    }

    /**
     * Returns a logical index associated with {@code local}.
     *
     * The logical index is not necessarily the frame index of the local. It *must not* be used to
     * directly index into the frame. Prefer emitting loads/stores in the bytecode itself (e.g.,
     * {@code builder.emitLoadLocal(bytecodeLocal)}). If you need to read a local outside of the
     * bytecode (e.g., in a Node), call {@link #getLocal} with the logical index.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @return the logical index of the local
     */
    @SuppressWarnings("unused")
    default int getLocalIndex(BytecodeLocal local) {
        throw new AbstractMethodError();
    }

    /**
     * Returns the current value of the local at index {@code i} in the frame. This method should be
     * used for uncommon scenarios, like when a Node needs to read a local directly from the frame.
     * Prefer reading locals directly in the bytecode (via
     * {@code builder.emitLoadLocal(bytecodeLocal)}) when possible.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param frame the frame to read locals from
     * @param localIndex the logical index of the local (as obtained by {@link #getLocalIndex()}).
     * @return an array of local values
     */
    @SuppressWarnings("unused")
    default Object getLocal(Frame frame, int localIndex) {
        throw new AbstractMethodError();
    }

    /**
     * Returns a new array containing the current value of each local in the frame. This method
     * should only be used for slow-path use cases (like frame introspection). Prefer regular local
     * load operations (via {@code builder.emitLoadLocal(bytecodeLocal)}) when possible.
     *
     * An operation can use this method by binding the root node to a specialization parameter (via
     * {@code @Bind("$root")}) and then invoking the method on the root node.
     *
     * The order of the locals corresponds to the order in which they were created using one of the
     * {@code createLocal()} overloads. It is up to the language to track the creation order.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @param frame the frame to read locals from
     * @return an array of local values
     */
    @SuppressWarnings("unused")
    default Object[] getLocals(Frame frame) {
        throw new AbstractMethodError();
    }

    /**
     * Returns a new array containing the {@link FrameDescriptor#getSlotName names} of locals, as
     * provided during bytecode building. If a local is not allocated using a {@code createLocal}
     * overload that takes a {@code name}, its name will be {@code null}.
     *
     * The order of the local names corresponds to the order in which the locals were created using
     * one of the {@code createLocal()} overloads. It is up to the language to track the creation
     * order.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @return an array of local names
     */
    @SuppressWarnings("unused")
    default Object[] getLocalNames() {
        throw new AbstractMethodError();
    }

    /**
     * Returns a new array containing the {@link FrameDescriptor#getSlotInfo infos} of locals, as
     * provided during bytecode building. If a local is not allocated using a {@code createLocal}
     * overload that takes an {@code info}, its info will be {@code null}.
     *
     * The order of the local infos corresponds to the order in which the locals were created using
     * one of the {@code createLocal()} overloads. It is up to the language to track the creation
     * order.
     *
     * This method will be generated by the Bytecode DSL. Do not override.
     *
     * @return an array of local names
     */
    @SuppressWarnings("unused")
    default Object[] getLocalInfos() {
        throw new AbstractMethodError();
    }

    /**
     * Copies all locals from the {@code source} frame to the {@code destination} frame. The frames
     * must have the same {@link Frame#getFrameDescriptor() layouts}.
     *
     * @param source the from to copy locals from
     * @param destination the frame to copy locals into
     */
    @SuppressWarnings("unused")
    default void copyLocals(Frame source, Frame destination) {
        throw new AbstractMethodError();
    }

    /**
     * Copies the first {@code length} locals from the {@code source} frame to the
     * {@code destination} frame. The frames must have the same {@link Frame#getFrameDescriptor()
     * layouts}. Compared to {@link #copyLocals(Frame, Frame)}, this method allows languages to
     * selectively copy a subset of the frame's locals.
     *
     * For example, suppose that in addition to regular locals, a root node uses temporary locals
     * for intermediate computations. Suppose also that the node needs to be able to compute the
     * values of its regular locals (e.g., for frame introspection). This method can be used to only
     * copy the regular locals and not the temporary locals -- assuming all of the regular locals
     * were allocated (using {@code createLocal()}) before the temporary locals.
     *
     * @param source the from to copy locals from
     * @param destination the frame to copy locals into
     * @param length the number of locals to copy.
     */
    @SuppressWarnings("unused")
    default void copyLocals(Frame source, Frame destination, int length) {
        throw new AbstractMethodError();
    }

    @SuppressWarnings("unused")
    default InstrumentableNode materializeInstrumentTree(Set<Class<? extends Tag>> materializedTags) {
        throw new AbstractMethodError();
    }

    /**
     * Helper method to dump the root node's bytecode.
     *
     * @return a string representation of the bytecode
     */
    @TruffleBoundary
    default String dump() {
        return dump(-1);
    }

    /**
     * Helper method to dump the root node's bytecode.
     *
     * @return a string representation of the bytecode
     */
    @TruffleBoundary
    default String dump(int highlightedBci) {
        BytecodeIntrospection id = getIntrospectionData();
        List<Instruction> instructions = id.getInstructions();
        List<ExceptionHandler> exceptions = id.getExceptionHandlers();
        List<SourceInformation> sourceInformation = id.getSourceInformation();

        return String.format("""
                        %s(name=%s)[
                            instructions(%s) = %s
                            exceptionHandlers(%s) = %s
                            sourceInformation(%s) = %s
                        ]""",
                        getClass().getSimpleName(),
                        ((RootNode) this).getQualifiedName(),
                        instructions.size(),
                        formatList(instructions, (i) -> i.getBci() == highlightedBci),
                        exceptions.size(),
                        formatList(exceptions, (e) -> highlightedBci >= e.getStartIndex() && highlightedBci < e.getEndIndex()),
                        sourceInformation != null ? sourceInformation.size() : "-",
                        formatList(sourceInformation, (s) -> highlightedBci >= s.getStartBci() && highlightedBci < s.getEndBci()));
    }

    private static <T> String formatList(List<T> list, Predicate<T> highlight) {
        if (list == null) {
            return "Not Available";
        } else if (list.isEmpty()) {
            return "Empty";
        }
        StringBuilder b = new StringBuilder();
        for (T o : list) {
            if (highlight.test(o)) {
                b.append("\n    ==> ");
            } else {
                b.append("\n        ");
            }
            b.append(o.toString());
        }
        return b.toString();
    }

}
