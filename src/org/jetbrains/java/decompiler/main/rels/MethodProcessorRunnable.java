// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.main.rels;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.cfg.ControlFlowGraph;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.modules.code.DeadCodeHelper;
import org.jetbrains.java.decompiler.modules.decompiler.*;
import org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.ExceptionDeobfuscator;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.MonitorExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.SynchronizedStatement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarProcessor;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;

import java.io.IOException;

public class MethodProcessorRunnable implements Runnable {
  public final Object lock = new Object();

  private final StructClass klass;
  private final StructMethod method;
  private final MethodDescriptor methodDescriptor;
  private final VarProcessor varProc;
  private final DecompilerContext parentContext;

  private volatile RootStatement root;
  private volatile Throwable error;
  private volatile boolean finished = false;

  public MethodProcessorRunnable(StructClass klass,
                                 StructMethod method,
                                 MethodDescriptor methodDescriptor,
                                 VarProcessor varProc,
                                 DecompilerContext parentContext) {
    this.klass = klass;
    this.method = method;
    this.methodDescriptor = methodDescriptor;
    this.varProc = varProc;
    this.parentContext = parentContext;
  }

  @Override
  public void run() {
    error = null;
    root = null;

    try {
      DecompilerContext.setCurrentContext(parentContext);
      root = codeToJava(klass, method, methodDescriptor, varProc);
    }
    catch (Throwable t) {
      error = t;
    }
    finally {
      DecompilerContext.setCurrentContext(null);
    }

    finished = true;
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  public static RootStatement codeToJava(StructClass cl, StructMethod mt, MethodDescriptor md, VarProcessor varProc) throws IOException {
    boolean isInitializer = CodeConstants.CLINIT_NAME.equals(mt.getName()); // for now static initializer only

    mt.expandData(cl);
    InstructionSequence seq = mt.getInstructionSequence();
    ControlFlowGraph graph = new ControlFlowGraph(seq);

    DeadCodeHelper.removeDeadBlocks(graph);
    graph.inlineJsr(cl, mt);

    // TODO: move to the start, before jsr inlining
    DeadCodeHelper.connectDummyExitBlock(graph);

    DeadCodeHelper.removeGotos(graph);

    ExceptionDeobfuscator.removeCircularRanges(graph);

    ExceptionDeobfuscator.restorePopRanges(graph);

    if (DecompilerContext.getOption(IFernflowerPreferences.REMOVE_EMPTY_RANGES)) {
      ExceptionDeobfuscator.removeEmptyRanges(graph);
    }

    if (DecompilerContext.getOption(IFernflowerPreferences.ENSURE_SYNCHRONIZED_MONITOR)) {
      // special case: search for 'synchronized' ranges w/o monitorexit instruction (as generated by Kotlin and Scala)
      DeadCodeHelper.extendSynchronizedRangeToMonitorexit(graph);
    }

    if (DecompilerContext.getOption(IFernflowerPreferences.NO_EXCEPTIONS_RETURN)) {
      // special case: single return instruction outside of a protected range
      DeadCodeHelper.incorporateValueReturns(graph);
    }

    //		ExceptionDeobfuscator.restorePopRanges(graph);
    ExceptionDeobfuscator.insertEmptyExceptionHandlerBlocks(graph);

    DeadCodeHelper.mergeBasicBlocks(graph);

    DecompilerContext.getCounterContainer().setCounter(CounterContainer.VAR_COUNTER, mt.getLocalVariables());

    if (ExceptionDeobfuscator.hasObfuscatedExceptions(graph)) {
      DecompilerContext.getLogger().writeMessage("Heavily obfuscated exception ranges found!", IFernflowerLogger.Severity.WARN);
      if (!ExceptionDeobfuscator.handleMultipleEntryExceptionRanges(graph)) {
        DecompilerContext.getLogger().writeMessage("Found multiple entry exception ranges which could not be splitted", IFernflowerLogger.Severity.WARN);
      }
      ExceptionDeobfuscator.insertDummyExceptionHandlerBlocks(graph, mt.getBytecodeVersion());
    }

    RootStatement root = DomHelper.parseGraph(graph, mt);

    FinallyProcessor fProc = new FinallyProcessor(md, varProc);
    while (fProc.iterateGraph(cl, mt, root, graph)) {
      root = DomHelper.parseGraph(graph, mt);
    }

    // remove synchronized exception handler
    // not until now because of comparison between synchronized statements in the finally cycle
    DomHelper.removeSynchronizedHandler(root);

    //		LabelHelper.lowContinueLabels(root, new HashSet<StatEdge>());

    SequenceHelper.condenseSequences(root);

    ClearStructHelper.clearStatements(root);

    ExprProcessor proc = new ExprProcessor(md, varProc);
    proc.processStatement(root, cl);

    SequenceHelper.condenseSequences(root);

    StackVarsProcessor stackProc = new StackVarsProcessor();

    do {
      stackProc.simplifyStackVars(root, mt, cl);
      varProc.setVarVersions(root);
    }
    while (new PPandMMHelper(varProc).findPPandMM(root));

    if (cl.isVersion(CodeConstants.BYTECODE_JAVA_9)) {
      ConcatenationHelper.simplifyStringConcat(root);
    }

    while (true) {
      LabelHelper.cleanUpEdges(root);

      while (true) {
        if (EliminateLoopsHelper.eliminateLoops(root, cl)) {
          continue;
        }

        MergeHelper.enhanceLoops(root);

        if (LoopExtractHelper.extractLoops(root)) {
          continue;
        }

        if (!IfHelper.mergeAllIfs(root)) {
          break;
        }
      }

      if (DecompilerContext.getOption(IFernflowerPreferences.IDEA_NOT_NULL_ANNOTATION)) {
        if (IdeaNotNullHelper.removeHardcodedChecks(root, mt)) {
          SequenceHelper.condenseSequences(root);
        }
      }

      stackProc.simplifyStackVars(root, mt, cl);
      varProc.setVarVersions(root);

      LabelHelper.identifyLabels(root);

      if (TryHelper.enhanceTryStats(root)) {
        continue;
      }

      if (InlineSingleBlockHelper.inlineSingleBlocks(root)) {
        continue;
      }

      // this has to be done last so it does not screw up the formation of for loops
      if (MergeHelper.makeDoWhileLoops(root)) {
        LabelHelper.cleanUpEdges(root);
        LabelHelper.identifyLabels(root);
      }

      // initializer may have at most one return point, so no transformation of method exits permitted
      if (isInitializer || !ExitHelper.condenseExits(root)) {
        break;
      }

      // FIXME: !!
      //if(!EliminateLoopsHelper.eliminateLoops(root)) {
      //  break;
      //}
    }

    // this has to be done after all inlining is done so the case values do not get reverted
    if (SwitchHelper.simplifySwitches(root)) {
      SequenceHelper.condenseSequences(root); // remove empty blocks
    }

    ExitHelper.removeRedundantReturns(root);

    SecondaryFunctionsHelper.identifySecondaryFunctions(root, varProc);

    cleanSynchronizedVar(root);

    varProc.setVarDefinitions(root);

    // Make sure to update assignments after setting the var definitions!
    SecondaryFunctionsHelper.updateAssignments(root);

    // Simplify casts at the end, to ensure behavior won't change too unpredictably
    SimplifyCastsProcessor.simplifyCasts(root);

    // must be the last invocation, because it makes the statement structure inconsistent
    // FIXME: new edge type needed
    LabelHelper.replaceContinueWithBreak(root);

    mt.releaseResources();

    return root;
  }

  public RootStatement getResult() throws Throwable {
    Throwable t = error;
    if (t != null) throw t;
    return root;
  }

  public boolean isFinished() {
    return finished;
  }

  public static void cleanSynchronizedVar(Statement stat) {
    for (Statement st : stat.getStats()) {
      cleanSynchronizedVar(st);
    }

    if (stat.type == Statement.TYPE_SYNCRONIZED) {
      SynchronizedStatement sync = (SynchronizedStatement)stat;
      if (sync.getHeadexprentList().get(0).type == Exprent.EXPRENT_MONITOR) {
        MonitorExprent mon = (MonitorExprent)sync.getHeadexprentList().get(0);
        for (Exprent e : sync.getFirst().getExprents()) {
          if (e.type == Exprent.EXPRENT_ASSIGNMENT) {
            AssignmentExprent ass = (AssignmentExprent)e;
            if (ass.getLeft().type == Exprent.EXPRENT_VAR) {
              VarExprent var = (VarExprent)ass.getLeft();
              if (ass.getRight().equals(mon.getValue()) && !var.isVarReferenced(stat.getParent())) {
                sync.getFirst().getExprents().remove(e);
                break;
              }
            }
          }
        }
      }
    }
  }
}
