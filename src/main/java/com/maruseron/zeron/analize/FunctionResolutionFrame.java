package com.maruseron.zeron.analize;

import com.maruseron.zeron.ast.Stmt;
import com.maruseron.zeron.domain.TypeDescriptor;
import com.maruseron.zeron.scan.Token;

import java.util.*;

final class FunctionResolutionFrame {
    private record WriteScopeState(Set<Token> scope, Set<Token> values) {}
    private record LoopState(LoopFlow loop, List<FlowState> breakStates,
                             List<FlowState> continueStates) {}

    record Checkpoint(List<WriteScopeState> flowWriteScopes,
                      List<LoopState> loopFlows,
                      List<TypeDescriptor> expectedReturnTypes,
                      Set<String> patternOutputNames,
                      Set<TypeDescriptor> raisedEffects,
                      Set<TypeDescriptor> raisedEffectValues,
                      FlowState flowState,
                      FlowState flowStateCopy,
                      int loopDepth,
                      boolean resolvingPattern,
                      boolean resolvingSumCaseCondition,
                      String currentClassName,
                      Stmt.ClassDecl currentMethodOwner,
                      List<Stmt.Field> initializerVisibleFields,
                      Stmt.ClassDecl dataInitializerOwner,
                      java.util.Map<String, Stmt.Field> dataInitializerWritableFields) {}

    final Deque<Set<Token>> flowWriteScopes = new ArrayDeque<>();
    final Deque<LoopFlow> loopFlows = new ArrayDeque<>();
    final Deque<TypeDescriptor> expectedReturnTypes = new ArrayDeque<>();
    Set<String> patternOutputNames = Set.of();
    Set<TypeDescriptor> raisedEffects;
    FlowState flowState = new FlowState();
    int loopDepth;
    boolean resolvingPattern;
    boolean resolvingSumCaseCondition;
    String currentClassName;
    Stmt.ClassDecl currentMethodOwner;
    List<Stmt.Field> initializerVisibleFields;
    Stmt.ClassDecl dataInitializerOwner;
    java.util.Map<String, Stmt.Field> dataInitializerWritableFields = java.util.Map.of();

    Checkpoint checkpoint() {
        final var writes = flowWriteScopes.stream()
                .map(scope -> new WriteScopeState(scope, Set.copyOf(scope))).toList();
        final var loops = loopFlows.stream()
                .map(loop -> new LoopState(loop, List.copyOf(loop.breakStates),
                        List.copyOf(loop.continueStates))).toList();
        return new Checkpoint(writes, loops, List.copyOf(expectedReturnTypes),
                patternOutputNames, raisedEffects,
                raisedEffects == null ? null : Set.copyOf(raisedEffects),
                flowState, flowState.copy(), loopDepth, resolvingPattern, resolvingSumCaseCondition,
                currentClassName, currentMethodOwner, initializerVisibleFields,
                dataInitializerOwner, dataInitializerWritableFields);
    }

    void restore(final Checkpoint checkpoint) {
        flowWriteScopes.clear();
        for (final var state : checkpoint.flowWriteScopes()) {
            state.scope().clear();
            state.scope().addAll(state.values());
            flowWriteScopes.addLast(state.scope());
        }
        loopFlows.clear();
        for (final var state : checkpoint.loopFlows()) {
            state.loop().breakStates.clear();
            state.loop().breakStates.addAll(state.breakStates());
            state.loop().continueStates.clear();
            state.loop().continueStates.addAll(state.continueStates());
            loopFlows.addLast(state.loop());
        }
        expectedReturnTypes.clear();
        expectedReturnTypes.addAll(checkpoint.expectedReturnTypes());
        patternOutputNames = checkpoint.patternOutputNames();
        if (checkpoint.raisedEffects() != null) {
            checkpoint.raisedEffects().clear();
            checkpoint.raisedEffects().addAll(checkpoint.raisedEffectValues());
        }
        raisedEffects = checkpoint.raisedEffects();
        checkpoint.flowState().restoreFrom(checkpoint.flowStateCopy());
        flowState = checkpoint.flowState();
        loopDepth = checkpoint.loopDepth();
        resolvingPattern = checkpoint.resolvingPattern();
        resolvingSumCaseCondition = checkpoint.resolvingSumCaseCondition();
        currentClassName = checkpoint.currentClassName();
        currentMethodOwner = checkpoint.currentMethodOwner();
        initializerVisibleFields = checkpoint.initializerVisibleFields();
        dataInitializerOwner = checkpoint.dataInitializerOwner();
        dataInitializerWritableFields = checkpoint.dataInitializerWritableFields();
    }
}
