/*
 * [The "BSD license"]
 *  Copyright (c) 2013 Terence Parr
 *  Copyright (c) 2013 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.antlr.v4.runtime.atn;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.dfa.DFAState;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;

import java.util.BitSet;

public class ProfilingATNSimulator extends ParserATNSimulator {
	protected final DecisionInfo[] decisions;
	protected int numDecisions;

	protected int _sllStopIndex;
	protected int _llStopIndex;

	protected int currentDecision;
	protected DFAState currentState;

	public ProfilingATNSimulator(Parser parser) {
		super(parser,
				parser.getInterpreter().atn,
				parser.getInterpreter().decisionToDFA,
				parser.getInterpreter().sharedContextCache);
		numDecisions = atn.decisionToState.size();
		decisions = new DecisionInfo[numDecisions];
		for (int i=0; i<numDecisions; i++) {
			decisions[i] = new DecisionInfo(i);
		}
	}

	@Override
	public int adaptivePredict(TokenStream input, int decision, ParserRuleContext outerContext) {
		try {
			this._sllStopIndex = -1;
			this._llStopIndex = -1;
			this.currentDecision = decision;
			long start = System.nanoTime(); // expensive but useful info
			int alt = super.adaptivePredict(input, decision, outerContext);
			long stop = System.nanoTime();
			decisions[decision].timeInPrediction += (stop-start);
			decisions[decision].invocations++;

			int SLL_k = _sllStopIndex - _startIndex + 1;
			decisions[decision].SLL_TotalLook += SLL_k;
			decisions[decision].SLL_MinLook = decisions[decision].SLL_MinLook==0 ? SLL_k : Math.min(decisions[decision].SLL_MinLook, SLL_k);
			if ( SLL_k > decisions[decision].SLL_MaxLook ) {
				decisions[decision].SLL_MaxLook = SLL_k;
				decisions[decision].SLL_MaxLookEvent =
						new LookaheadEventInfo(decision, null, input, _startIndex, _sllStopIndex, false);
			}

			if (_llStopIndex >= 0) {
				int LL_k = _llStopIndex - _startIndex + 1;
				decisions[decision].LL_TotalLook += LL_k;
				decisions[decision].LL_MinLook = decisions[decision].LL_MinLook==0 ? LL_k : Math.min(decisions[decision].LL_MinLook, LL_k);
				if ( LL_k > decisions[decision].LL_MaxLook ) {
					decisions[decision].LL_MaxLook = LL_k;
					decisions[decision].LL_MaxLookEvent =
							new LookaheadEventInfo(decision, null, input, _startIndex, _llStopIndex, true);
				}
			}

			return alt;
		}
		finally {
			this.currentDecision = -1;
		}
	}

	@Override
	protected DFAState getExistingTargetState(DFAState previousD, int t) {
		// this method is called after each time the input position advances
		// during SLL prediction
		_sllStopIndex = _input.index();

		DFAState existingTargetState = super.getExistingTargetState(previousD, t);
		if ( existingTargetState!=null ) {
			decisions[currentDecision].SLL_DFATransitions++; // count only if we transition over a DFA state
			if ( existingTargetState==ERROR ) {
				decisions[currentDecision].errors.add(
						new ErrorInfo(currentDecision, previousD.configs, _input, _startIndex, _sllStopIndex, false)
				);
			}
		}

		currentState = existingTargetState;
		return existingTargetState;
	}

	@Override
	protected DFAState computeTargetState(DFA dfa, DFAState previousD, int t) {
		DFAState state = super.computeTargetState(dfa, previousD, t);
		currentState = state;
		return state;
	}

	@Override
	protected ATNConfigSet computeReachSet(ATNConfigSet closure, int t, boolean fullCtx) {
		if (fullCtx) {
			// this method is called after each time the input position advances
			// during full context prediction
			_llStopIndex = _input.index();
		}

		ATNConfigSet reachConfigs = super.computeReachSet(closure, t, fullCtx);
		if (fullCtx) {
			decisions[currentDecision].LL_ATNTransitions++; // count computation even if error
			if ( reachConfigs!=null ) {
			}
			else { // no reach on current lookahead symbol. ERROR.
				// TODO: does not handle delayed errors per getSynValidOrSemInvalidAltThatFinishedDecisionEntryRule()
				decisions[currentDecision].errors.add(
					new ErrorInfo(currentDecision, closure, _input, _startIndex, _llStopIndex, true)
				);
			}
		}
		else {
			decisions[currentDecision].SLL_ATNTransitions++;
			if ( reachConfigs!=null ) {
			}
			else { // no reach on current lookahead symbol. ERROR.
				decisions[currentDecision].errors.add(
					new ErrorInfo(currentDecision, closure, _input, _startIndex, _sllStopIndex, false)
				);
			}
		}
		return reachConfigs;
	}

	@Override
	protected BitSet evalSemanticContext(DFAState.PredPrediction[] predPredictions,
										 ParserRuleContext outerContext,
										 boolean complete) {
		/* Force complete prediction for the purpose of gathering statistical
		 * results. If the caller requested incomplete evaluation, the result is
		 * modified before returning to behave as though incomplete evaluation
		 * was used.
		 */
		BitSet predictions = super.evalSemanticContext(predPredictions, outerContext, true);
		// must re-evaluate all preds as predictions can't map back to pred eval uniquely
		int n = predPredictions.length;
		boolean[] results = new boolean[n];
		int i = 0;
		// FOR INTERPRETER, these are all true unless precedence preds!
		for (DFAState.PredPrediction pair : predPredictions) {
			if ( pair.pred!=SemanticContext.NONE ) {
				results[i] = pair.pred.eval(parser, outerContext);
			}
			i++;
		}

		boolean fullContext = _llStopIndex >= 0;
		int stopIndex = fullContext ? _llStopIndex : _sllStopIndex;
		decisions[currentDecision].predicateEvals.add(
			new PredicateEvalInfo(currentState, currentDecision, _input, _startIndex, stopIndex, results, predictions)
		);

		if (!complete && !predictions.isEmpty()) {
			int minimum = predictions.nextSetBit(0);
			predictions = new BitSet();
			predictions.set(minimum);
		}

		return predictions;
	}

	@Override
	protected void reportContextSensitivity(@NotNull DFA dfa, int prediction, @NotNull ATNConfigSet configs, int startIndex, int stopIndex) {
		decisions[currentDecision].contextSensitivities.add(
			new ContextSensitivityInfo(currentDecision, configs, _input, startIndex, stopIndex)
		);
		super.reportContextSensitivity(dfa, prediction, configs, startIndex, stopIndex);
	}

	@Override
	protected void reportAttemptingFullContext(@NotNull DFA dfa, @Nullable BitSet conflictingAlts, @NotNull ATNConfigSet configs, int startIndex, int stopIndex) {
		decisions[currentDecision].LL_Fallback++;
		super.reportAttemptingFullContext(dfa, conflictingAlts, configs, startIndex, stopIndex);
	}

	@Override
	protected void reportAmbiguity(@NotNull DFA dfa, DFAState D, int startIndex, int stopIndex, boolean exact, @Nullable BitSet ambigAlts, @NotNull ATNConfigSet configs) {
		decisions[currentDecision].ambiguities.add(
			new AmbiguityInfo(currentDecision, configs, _input, startIndex, stopIndex, configs.fullCtx)
		);
		super.reportAmbiguity(dfa, D, startIndex, stopIndex, exact, ambigAlts, configs);
	}

	// ---------------------------------------------------------------------

	public DecisionInfo[] getDecisionInfo() {
		return decisions;
	}
}
