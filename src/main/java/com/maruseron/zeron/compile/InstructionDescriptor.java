package com.maruseron.zeron.compile;

import com.maruseron.zeron.domain.TypeDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ClassDesc;

/* examples:
  let a = 5;
  let b = 7;
  print(a + b);

  { iconst_5,                                    push, 1, Int,  I,                j.l.Integer  }
  { istore_1,                                    pop,  1, Unit, V,                null         }
  { bipush 7,                                    push, 1, Int,  I,                j.l.Integer  }
  { istore_2,                                    pop,  1, Unit, V,                null         }
  { getstatic System.out,                        push, 1, null, j.io.PrintStream, null         }
  { iload_1,                                     push, 1, Int,  I,                j.l.Integer  }
  { iload_2,                                     push, 1, Int,  I,                j.l.Integer  }
  { iadd,                                        rplc, 2, Int,  I,                j.l.Integer  }
  { invokevirtual println(Ljava/lang/Object;)V,  pop,  2, Unit, V,                null         }
  { return,                                      none, 0, Unit, V,                null         }
 */
public record InstructionDescriptor(Instruction instruction, Opcode.Kind kind, int size,
                                    @Nullable TypeDescriptor zeronType, ClassDesc javaType,
                                    @Nullable ClassDesc autoBoxer) {

    public InstructionDescriptor {
        switch (kind) {
            case LOAD -> {
                switch (instruction.opcode()) {
                    case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3, ILOAD_W ->
                        zeronType = TypeDescriptor.ofInt();
                    case DLOAD, DLOAD_0, DLOAD_1, DLOAD_2, DLOAD_3, DLOAD_W ->
                        zeronType = TypeDescriptor.ofFloat();
                    default -> throw new IllegalStateException();
                }
            }
            case OPERATOR -> {
                switch (instruction.opcode()) {
                    case IADD, ISUB, IMUL, IDIV, INEG ->
                        zeronType = TypeDescriptor.ofInt();
                    case DADD, DSUB, DMUL, DDIV, DNEG ->
                        zeronType = TypeDescriptor.ofFloat();
                }
            }
            case CONSTANT -> {
                switch (instruction.opcode()) {
                    case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                         BIPUSH, SIPUSH ->
                        zeronType = TypeDescriptor.ofInt();
                    case DCONST_0, DCONST_1 ->
                        zeronType = TypeDescriptor.ofFloat();
                    case LDC, LDC_W, LDC2_W -> {
                        final var constInstruction =
                                (ConstantInstruction.LoadConstantInstruction)instruction;
                        switch (constInstruction.typeKind()) {
                            case INT -> zeronType = TypeDescriptor.ofInt();
                            case DOUBLE -> zeronType = TypeDescriptor.ofFloat();
                        }
                    }
                }
            }
        }
        if (zeronType != null) {
            javaType  = TypeDescriptor.toJavaClassDesc(zeronType);
            autoBoxer = TypeDescriptor.toJavaWrapper(zeronType);
        }
    }

    @Override
    public @NotNull String toString() {
        return "InstructionDescriptor " + instruction +
                " [ kind=" + kind +
                ", size=" + size +
                ", zeronType=" + zeronType +
                ", javaType=" + javaType +
                ", autoBoxer=" + autoBoxer +
                " ]";
    }
}
