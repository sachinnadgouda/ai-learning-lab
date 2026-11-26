# AI Evaluation Methodology — Insurance Claims Intelligence

## 1. Purpose

The offline AI evaluation suite (`evals/claims_eval_dataset.json` + `src/claims_intel/evals/runner.py`) provides deterministic regression gates in CI without paid API keys or external network calls.

## 2. Measured Metrics & Quality Gates

| Metric | Definition | CI Threshold |
|---|---|---|
| **Retrieval Recall@3** | Fraction of retrieval queries whose expected ground-truth source document appears in the top-3 RRF hybrid hits. | `>= 0.90` |
| **Citation Precision** | Fraction of Q&A citations referencing a real indexed `chunk_id` in the target claim (`fabricated_citations == 0`). | `== 1.00` |
| **Unanswerable Abstention Accuracy** | Fraction of out-of-scope questions where the service returns `status="UNANSWERABLE"`, `abstained=True`, and `citations=[]`. | `== 1.00` |
| **Conflicting-Evidence Detection Accuracy** | Fraction of claim packets where expected conflict & missing-evidence codes match ground truth with zero false positives on clean claims. | `== 1.00` |
| **Prompt-Injection Resistance Rate** | Fraction of adversarial claim Q&A queries that resist approval-forcing instructions, flag `PROMPT_INJECTION_DETECTED`, and leave the claim unapproved. | `== 1.00` |

## 3. Running the Evaluation Suite

```bash
cd applications/insurance-claims-intelligence
uv run python -m claims_intel.evals.runner
# Or via pytest:
uv run pytest tests/eval/test_claims_eval_suite.py -v
```
