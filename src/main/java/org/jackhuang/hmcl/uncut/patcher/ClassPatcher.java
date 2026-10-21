package org.jackhuang.hmcl.uncut.patcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites {@code RegionUtils.isIsChinaMainlandNetwork()} so that it always returns {@code false}.
 * <p>
 * The official HMCL build uses this method to disable the download pages. Its upstream body resolves
 * the cached network region (a private field, filled by a request to {@code https://ipwho.is}). Turning
 * the method into a constant keeps the call sites intact while never disabling anything, and it also
 * stops the network probe from ever being executed.
 */
public final class ClassPatcher {
    static final String METHOD_NAME = "isIsChinaMainlandNetwork";
    static final String METHOD_DESC = "()Z";

    private static final String FINGERPRINT_METHOD = "checkChinaMainlandNetwork";
    private static final String FINGERPRINT_FIELD = "isChinaMainlandNetwork";

    public static final class AlreadyPatchedException extends Exception {
        AlreadyPatchedException() {
            super("这个方法已经是恒返回 false 的形态，文件可能已经打过补丁");
        }
    }

    public static final class UnexpectedShapeException extends Exception {
        UnexpectedShapeException(String message) {
            super(message);
        }
    }

    private ClassPatcher() {
    }

    public static byte[] patch(byte[] classBytes) throws Exception {
        ClassNode node = read(classBytes);
        MethodNode target = findTarget(node);
        if (target == null) {
            throw new UnexpectedShapeException(
                    "找不到 " + METHOD_NAME + METHOD_DESC + "，这可能不是预期版本的 HMCL");
        }

        if (isReturnFalse(target)) {
            throw new AlreadyPatchedException();
        }
        if ((target.access & Opcodes.ACC_STATIC) == 0) {
            throw new UnexpectedShapeException("门禁方法不是静态方法，上游可能改过实现：" + METHOD_NAME);
        }
        if (!hasFingerprint(target)) {
            throw new UnexpectedShapeException(
                    "门禁方法形状与预期不符（上游可能改过实现）：" + METHOD_NAME + METHOD_DESC);
        }

        target.instructions.clear();
        target.tryCatchBlocks.clear();
        target.localVariables = null;
        target.visibleLocalVariableAnnotations = null;
        target.invisibleLocalVariableAnnotations = null;
        target.instructions.add(new InsnNode(Opcodes.ICONST_0));
        target.instructions.add(new InsnNode(Opcodes.IRETURN));
        target.maxStack = 1;
        target.maxLocals = 0;

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static boolean isPatched(byte[] classBytes) throws Exception {
        MethodNode target = findTarget(read(classBytes));
        return target != null && isReturnFalse(target);
    }

    public static void verifyPatched(byte[] classBytes) throws Exception {
        MethodNode target = findTarget(read(classBytes));
        if (target == null) {
            throw new UnexpectedShapeException("补丁后找不到 " + METHOD_NAME + METHOD_DESC);
        }
        if (!isReturnFalse(target)) {
            throw new UnexpectedShapeException("补丁后方法体不是 iconst_0 / ireturn");
        }
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static MethodNode findTarget(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(METHOD_NAME) && method.desc.equals(METHOD_DESC)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isReturnFalse(MethodNode method) {
        if (method.instructions.size() != 2) {
            return false;
        }
        AbstractInsnNode first = method.instructions.get(0);
        AbstractInsnNode second = method.instructions.get(1);
        return first.getOpcode() == Opcodes.ICONST_0 && second.getOpcode() == Opcodes.IRETURN;
    }

    private static boolean hasFingerprint(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.name.equals(FINGERPRINT_METHOD)) {
                return true;
            }
            if (insn instanceof FieldInsnNode field && field.name.equals(FINGERPRINT_FIELD)) {
                return true;
            }
        }
        return false;
    }
}
