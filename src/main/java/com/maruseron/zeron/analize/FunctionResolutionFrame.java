package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.*;

final class FunctionResolutionFrame {
    final Deque<Set<Token>> flowWriteScopes = new ArrayDeque<>();
    final Deque<LoopFlow> loopFlows = new ArrayDeque<>();
    final Deque<TypeDescriptor> expectedReturnTypes = new ArrayDeque<>();
    Set<String> patternOutputNames = Set.of();
    Set<TypeDescriptor> raisedEffects;
    FlowState flowState = new FlowState();
    int loopDepth;
    boolean resolvingPattern;
    String currentClassName;
    Stmt.ClassDecl currentMethodOwner;
    List<Stmt.Field> initializerVisibleFields;
}
