package com.maruseron.zeron.compile;

import com.maruseron.zeron.domain.FunctionDescriptor;
import com.maruseron.zeron.domain.TypeDescriptor;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/* example:
fn main() {
  let a = 5;
  let b = 7;
  print(a + b);
}

function main(): Unit
  descriptor: ()Void
  locals:2 stack:3 args:0
  { iconst_5,                  push, 1, Int,   I,                j.l.Integer  }
  { istore_1,                  pop,  1, Never, V,                null         }
  { bipush 7,                  push, 1, Int,   I,                j.l.Integer  }
  { istore_2,                  pop,  1, Never, V,                null         }
  { getstatic System.out,      push, 1, null,  j.io.PrintStream, null         }
  { iload_1,                   push, 1, Int,   I,                j.l.Integer  }
  { iload_2,                   push, 1, Int,   I,                j.l.Integer  }
  { iadd,                      rplc, 2, Int,   I,                j.l.Integer  }
  { invokevirtual println(I)V, pop,  2, Never, V,                null         }
  { return,                    none, 0, Unit,  V,                null         }
 */
public final class FunctionModel {
    private final String name;
    private final FunctionDescriptor typeDescriptor;
    private final List<InstructionDescriptor> instructions = new ArrayList<>();

    public FunctionModel(String name, FunctionDescriptor typeDescriptor) {
        this.name = name;
        this.typeDescriptor = typeDescriptor;
    }

    public String name() {
        return name;
    }

    public FunctionModel add(final Instruction i,
                             final Opcode.Kind type,
                             final TypeDescriptor zeronType,
                             final ClassDesc javaType,
                   @Nullable final ClassDesc autoBoxer) {
        instructions.add(new InstructionDescriptor(i, type, i.sizeInBytes(), zeronType,
                javaType,
                autoBoxer));
        return this;
    }

    public List<InstructionDescriptor> code() {
        return instructions.stream().toList();
    }

    @Override
    public String toString() {
        return "function " + name + "(" + typeDescriptor.parameters().stream().map(TypeDescriptor::name).collect(Collectors.joining(", ")) + "): " + typeDescriptor.returnType() + "\n"
            + "  code:\n"
            + "    " + instructions.stream().map(Objects::toString).collect(Collectors.joining(
                    "\n    "));
    }
}
