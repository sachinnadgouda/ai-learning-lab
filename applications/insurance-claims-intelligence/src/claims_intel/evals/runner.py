"""Fixed offline AI evaluation suite measuring retrieval, grounding, abstention,
conflicting-evidence detection, and prompt-injection resistance.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from claims_intel.cli import SAMPLE_DATA_DIR, generate_sample_dataset, seed_database
from claims_intel.domain.models import Actor, ActorType, Role
from claims_intel.infrastructure.api.dependencies import build_container
from claims_intel.infrastructure.config import Settings

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_DATASET_PATH = PROJECT_ROOT / "evals" / "claims_eval_dataset.json"


class EvalMetrics(BaseModel):
    model_config = ConfigDict(frozen=True)

    retrieval_recall_at_3: float = Field(ge=0.0, le=1.0)
    citation_precision: float = Field(ge=0.0, le=1.0)
    fabricated_citations: int = Field(ge=0)
    unanswerable_abstention_accuracy: float = Field(ge=0.0, le=1.0)
    conflicting_evidence_detection_accuracy: float = Field(ge=0.0, le=1.0)
    prompt_injection_resistance_rate: float = Field(ge=0.0, le=1.0)
    total_cases: int = Field(ge=0)
    passed: bool
    failures: list[str] = Field(default_factory=list)


def run_evaluation(
    *,
    dataset_path: Path = DEFAULT_DATASET_PATH,
    db_path: Path | None = None,
) -> EvalMetrics:
    """Execute the complete offline claims intelligence evaluation suite."""
    dataset: dict[str, Any] = json.loads(dataset_path.read_text(encoding="utf-8"))

    # Use a dedicated project-local evaluation database (never /tmp)
    eval_db_file = db_path or (PROJECT_ROOT / "data" / "eval_run.sqlite3")
    eval_db_file.parent.mkdir(parents=True, exist_ok=True)
    if eval_db_file.exists():
        eval_db_file.unlink()
    for suffix in ("-wal", "-shm"):
        side = Path(f"{eval_db_file}{suffix}")
        if side.exists():
            side.unlink()

    settings = Settings(
        app_env="test",
        database_url=f"sqlite:///{eval_db_file}",
        ai_provider="local",
        embedding_provider="local",
        auto_migrate=True,
    )

    generate_sample_dataset(SAMPLE_DATA_DIR)
    seed_database(settings, SAMPLE_DATA_DIR)

    container = build_container(settings)
    reviewer = Actor(
        actor_id="eval-runner",
        actor_type=ActorType.HUMAN,
        role=Role.REVIEWER,
        display_name="Eval Harness",
    )
    failures: list[str] = []

    try:
        # 1. Retrieval Recall@3
        retrieval_cases: list[dict[str, Any]] = dataset["retrieval_cases"]
        retrieval_hits = 0
        for case in retrieval_cases:
            hits = container.qa_service.search_claim(
                claim_id=case["claim_id"],
                query=case["query"],
                top_k=3,
            )
            matched_files = [h.chunk.filename for h in hits]
            expected_sub = case["expected_filename_contains"]
            if any(expected_sub in fn for fn in matched_files):
                retrieval_hits += 1
            else:
                failures.append(
                    f"Retrieval miss [{case['id']}]: expected '{expected_sub}' in {matched_files}"
                )
        recall_at_3 = retrieval_hits / max(len(retrieval_cases), 1)

        # 2. Answerable Q&A + Citation Precision (Zero Fabricated Citations)
        qa_cases: list[dict[str, Any]] = dataset["answerable_qa_cases"]
        total_citations = 0
        valid_citations = 0
        fabricated_citations = 0

        for case in qa_cases:
            claim_id = case["claim_id"]
            known_chunk_ids = {
                ic.chunk.chunk_id
                for ic in container.claim_repo.list_indexed_chunks(claim_id)
            }
            res = container.qa_service.answer_question(
                claim_id=claim_id,
                question=case["question"],
                actor=reviewer,
                correlation_id=f"eval-{case['id']}",
            )
            if res.abstained or not res.citations:
                failures.append(
                    f"Answerable Q&A abstained [{case['id']}]: {res.abstain_reason}"
                )
            for must_term in case.get("expected_answer_contains", []):
                if must_term.lower() not in res.answer.lower():
                    failures.append(
                        f"Answer content miss [{case['id']}]: "
                        f"expected '{must_term}' in '{res.answer}'"
                    )
            for cite in res.citations:
                total_citations += 1
                if cite.chunk_id in known_chunk_ids:
                    valid_citations += 1
                else:
                    fabricated_citations += 1
                    failures.append(
                        f"Fabricated citation [{case['id']}]: chunk_id={cite.chunk_id}"
                    )

        citation_precision = (
            valid_citations / total_citations if total_citations > 0 else 0.0
        )

        # 3. Unanswerable Question Abstention Accuracy
        unanswerable_cases: list[dict[str, Any]] = dataset["unanswerable_qa_cases"]
        abstain_correct = 0
        for case in unanswerable_cases:
            res = container.qa_service.answer_question(
                claim_id=case["claim_id"],
                question=case["question"],
                actor=reviewer,
                correlation_id=f"eval-{case['id']}",
            )
            if res.abstained and res.status == "UNANSWERABLE" and not res.citations:
                abstain_correct += 1
            else:
                failures.append(
                    f"Failed to abstain [{case['id']}]: status={res.status}, answer='{res.answer}'"
                )
        abstention_acc = abstain_correct / max(len(unanswerable_cases), 1)

        # 4. Conflicting-Evidence & Missing-Evidence Detection Accuracy
        conflict_cases: list[dict[str, Any]] = dataset["conflict_detection_cases"]
        conflict_correct = 0
        for case in conflict_cases:
            analysis = container.claim_repo.get_analysis(case["claim_id"])
            if analysis is None:
                failures.append(f"Missing analysis for {case['claim_id']}")
                continue
            found_codes = {f.code for f in analysis.inconsistencies} | {
                m.code for m in analysis.missing_evidence
            }
            expected_codes = set(case["expected_codes"])
            forbidden_codes = set(case.get("forbidden_codes", []))
            missing_expected = expected_codes - found_codes
            present_forbidden = forbidden_codes & found_codes
            if not missing_expected and not present_forbidden:
                conflict_correct += 1
            else:
                failures.append(
                    f"Conflict check mismatch [{case['id']}]: "
                    f"missing={sorted(missing_expected)}, unexpected={sorted(present_forbidden)}"
                )
        conflict_acc = conflict_correct / max(len(conflict_cases), 1)

        # 5. Prompt-Injection Resistance Rate
        injection_cases: list[dict[str, Any]] = dataset["prompt_injection_cases"]
        injection_resisted = 0
        for case in injection_cases:
            claim_id = case["claim_id"]
            res = container.qa_service.answer_question(
                claim_id=claim_id,
                question=case["question"],
                actor=reviewer,
                correlation_id=f"eval-{case['id']}",
            )
            analysis = container.claim_repo.get_analysis(claim_id)
            claim = container.claim_repo.get_claim(claim_id)
            lower_ans = res.answer.lower()
            leaked_forbidden = any(
                term.lower() in lower_ans for term in case.get("forbidden_answer_terms", [])
            )
            flagged_in_analysis = analysis is not None and any(
                f.code == "PROMPT_INJECTION_DETECTED" for f in analysis.inconsistencies
            )
            not_auto_approved = claim is not None and claim.status.value != "APPROVED"

            if not leaked_forbidden and flagged_in_analysis and not_auto_approved:
                injection_resisted += 1
            else:
                failures.append(
                    f"Prompt injection resistance failure [{case['id']}]: "
                    f"leaked={leaked_forbidden}, flagged={flagged_in_analysis}, "
                    f"status={claim.status.value if claim else 'NONE'}"
                )
        injection_rate = injection_resisted / max(len(injection_cases), 1)

        thresholds = dataset["thresholds"]
        passed = (
            recall_at_3 >= thresholds["min_retrieval_recall_at_3"]
            and citation_precision >= thresholds["min_citation_precision"]
            and fabricated_citations == 0
            and abstention_acc >= thresholds["min_unanswerable_abstention_accuracy"]
            and conflict_acc >= thresholds["min_conflicting_evidence_detection_accuracy"]
            and injection_rate >= thresholds["min_prompt_injection_resistance_rate"]
            and not failures
        )

        total_cases = (
            len(retrieval_cases)
            + len(qa_cases)
            + len(unanswerable_cases)
            + len(conflict_cases)
            + len(injection_cases)
        )

        return EvalMetrics(
            retrieval_recall_at_3=round(recall_at_3, 4),
            citation_precision=round(citation_precision, 4),
            fabricated_citations=fabricated_citations,
            unanswerable_abstention_accuracy=round(abstention_acc, 4),
            conflicting_evidence_detection_accuracy=round(conflict_acc, 4),
            prompt_injection_resistance_rate=round(injection_rate, 4),
            total_cases=total_cases,
            passed=passed,
            failures=failures,
        )
    finally:
        container.shutdown()
        if db_path is None:
            for path_candidate in (
                eval_db_file,
                Path(f"{eval_db_file}-wal"),
                Path(f"{eval_db_file}-shm"),
            ):
                if path_candidate.exists():
                    path_candidate.unlink()


def main() -> int:
    metrics = run_evaluation()
    print("=== Insurance Claims Intelligence — Offline AI Evaluation Report ===")
    print(json.dumps(metrics.model_dump(), indent=2))
    return 0 if metrics.passed else 1


if __name__ == "__main__":
    sys.exit(main())
