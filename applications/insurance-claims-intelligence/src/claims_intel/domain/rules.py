"""Deterministic claims intelligence rules: classification, extraction, entity resolution,
policy coverage lookup, inconsistency & missing-evidence checks, and weighted risk scoring.
"""

from __future__ import annotations

import re
from collections import defaultdict
from collections.abc import Sequence
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from claims_intel.domain.injection import (
    detect_prompt_injection_in_chunks,
    strip_injection_lines,
)
from claims_intel.domain.models import (
    CoverageVerdict,
    DocumentRecord,
    DocumentType,
    EntityLink,
    EvidenceChunk,
    EvidenceRef,
    ExtractedFact,
    FactClassification,
    InconsistencyFinding,
    MissingEvidenceItem,
    PolicyCoverageResult,
    RiskAssessment,
    RiskIndicator,
    Severity,
)

# --- Document Type Classification ---

_FILENAME_TYPE_HINTS: tuple[tuple[str, DocumentType], ...] = (
    ("police", DocumentType.POLICE_REPORT),
    ("incident_report", DocumentType.POLICE_REPORT),
    ("policy", DocumentType.POLICY_SCHEDULE),
    ("schedule", DocumentType.POLICY_SCHEDULE),
    ("coverage", DocumentType.POLICY_SCHEDULE),
    ("invoice", DocumentType.INVOICE),
    ("receipt", DocumentType.INVOICE),
    ("billing", DocumentType.INVOICE),
    ("estimate", DocumentType.REPAIR_ESTIMATE),
    ("repair", DocumentType.REPAIR_ESTIMATE),
    ("medical", DocumentType.MEDICAL_REPORT),
    ("hospital", DocumentType.MEDICAL_REPORT),
    ("claim_form", DocumentType.CLAIM_FORM),
    ("fnol", DocumentType.CLAIM_FORM),
    ("claim", DocumentType.CLAIM_FORM),
    ("photo", DocumentType.PHOTO_EVIDENCE),
    ("damage_img", DocumentType.PHOTO_EVIDENCE),
    ("email", DocumentType.CORRESPONDENCE),
    ("letter", DocumentType.CORRESPONDENCE),
)


def classify_document(filename: str, mime_type: str, text: str) -> DocumentType:
    """Deterministically classify a claim document using MIME type, filename, and text cues."""
    if mime_type.startswith("image/"):
        lower_name = filename.lower()
        if "invoice" in lower_name or "receipt" in lower_name:
            return DocumentType.INVOICE
        return DocumentType.PHOTO_EVIDENCE

    lower_name = filename.lower()
    for token, doc_type in _FILENAME_TYPE_HINTS:
        if token in lower_name:
            return doc_type

    sample = text[:2000].lower()
    if "policy schedule" in sample or "covered perils" in sample or "effective period" in sample:
        return DocumentType.POLICY_SCHEDULE
    if "police department" in sample or "officer badge" in sample or "case number" in sample:
        return DocumentType.POLICE_REPORT
    if "repair estimate" in sample or "labor hours" in sample:
        return DocumentType.REPAIR_ESTIMATE
    if "invoice number" in sample or "total due" in sample or "invoice total" in sample:
        return DocumentType.INVOICE
    if "first notice of loss" in sample or "claimant name" in sample or "claimed amount" in sample:
        return DocumentType.CLAIM_FORM
    if "diagnosis" in sample or "attending physician" in sample:
        return DocumentType.MEDICAL_REPORT
    if "dear " in sample or "subject:" in sample:
        return DocumentType.CORRESPONDENCE
    return DocumentType.UNKNOWN


# --- Deterministic Fact Extraction ---

_POLICY_NUM_RE = re.compile(
    r"(?i)(?:policy\s*(?:number|no\.?|#)?|policy_number)\s*[:=\"]+\s*([A-Z]{2,5}-[A-Z0-9-]{4,24})"
)
_CLAIMANT_RE = re.compile(
    r"(?im)^\s*(?:\"?(?:claimant(?:[\s_]+name)?|insured(?:[\s_]+name)?|"
    r"policyholder|customer[\s_]+name)\"?)"
    r"\s*[:=]\s*\"?([A-Z][A-Za-z.\-']+(?:\s+[A-Z][A-Za-z.\-']+){1,3})\"?\s*,?\s*$"
)
_INCIDENT_DATE_RE = re.compile(
    r"(?i)(?:incident\s*date|date\s*of\s*loss|loss\s*date|incident_date)\s*[:=\"]+\s*"
    r"(\d{4}-\d{2}-\d{2}|\d{2}/\d{2}/\d{4})"
)
_EFFECTIVE_PERIOD_RE = re.compile(
    r"(?i)(?:effective\s*period|policy\s*period|coverage\s*period)\s*[:=\"]+\s*"
    r"(\d{4}-\d{2}-\d{2})\s*(?:to|through|-)\s*(\d{4}-\d{2}-\d{2})"
)
_EFFECTIVE_START_RE = re.compile(
    r"(?i)(?:effective_start|effective\s*from|policy\s*start)\s*[:=\"]+\s*(\d{4}-\d{2}-\d{2})"
)
_EFFECTIVE_END_RE = re.compile(
    r"(?i)(?:effective_end|effective\s*to|expiration\s*date|policy\s*end)\s*[:=\"]+\s*"
    r"(\d{4}-\d{2}-\d{2})"
)
_CLAIMED_AMT_RE = re.compile(
    r"(?i)(?:claimed\s*amount|claim\s*amount|total\s*claimed|claimed_amount)\s*[:=\"]+\s*"
    r"\$?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"
)
_INVOICE_AMT_RE = re.compile(
    r"(?i)(?:invoice\s*total|total\s*(?:invoice\s*)?amount|total\s*due|grand\s*total|"
    r"estimate\s*total|invoice_total|total_usd)\s*[:=\"]+\s*\$?\s*"
    r"([0-9][0-9,]*(?:\.[0-9]{1,2})?)"
)
_DEDUCTIBLE_RE = re.compile(
    r"(?i)(?:deductible)\s*[:=\"]+\s*\$?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"
)
_COVERAGE_LIMIT_RE = re.compile(
    r"(?i)(?:coverage\s*limit|policy\s*limit|maximum\s*limit|coverage_limit)\s*[:=\"]+\s*"
    r"\$?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)"
)
_VIN_RE = re.compile(
    r"(?i)(?:vin|vehicle\s*id|property\s*id|asset_id)\s*[:=\"]+\s*([A-HJ-NPR-Z0-9-]{6,20})"
)
_VENDOR_RE = re.compile(
    r"(?im)^\s*(?:\"?(?:vendor|provider|repair\s*shop|shop_name|merchant)\"?)\s*[:=]\s*"
    r"\"?([A-Za-z0-9][A-Za-z0-9 &.,'\-]{2,60})\"?\s*,?\s*$"
)
_INCIDENT_TYPE_RE = re.compile(
    r"(?im)^\s*(?:\"?(?:incident\s*type|loss\s*type|peril|cause\s*of\s*loss|incident_type)\"?)"
    r"\s*[:=]\s*\"?([A-Za-z][A-Za-z /\-]{2,40})\"?\s*,?\s*$"
)
_COVERED_PERILS_RE = re.compile(
    r"(?im)^\s*(?:\"?covered\s*perils\"?)\s*[:=]\s*\"?([^\n\"]{3,200})\"?\s*$"
)
_EXCLUSIONS_RE = re.compile(
    r"(?im)^\s*(?:\"?exclusions\"?)\s*[:=]\s*\"?([^\n\"]{3,200})\"?\s*$"
)


def _make_ref(chunk: EvidenceChunk, match_text: str) -> EvidenceRef:
    return EvidenceRef(
        chunk_id=chunk.chunk_id,
        document_id=chunk.document_id,
        filename=chunk.filename,
        page_number=chunk.page_number,
        char_start=chunk.char_start,
        char_end=chunk.char_end,
        quote=match_text.strip()[:350],
    )


def parse_iso_or_us_date(raw: str) -> date | None:
    cleaned = raw.strip()
    for fmt in ("%Y-%m-%d", "%m/%d/%Y"):
        try:
            return datetime.strptime(cleaned, fmt).date()
        except ValueError:
            continue
    return None


def parse_decimal_amount(raw: str) -> Decimal | None:
    cleaned = raw.replace(",", "").replace("$", "").strip()
    try:
        val = Decimal(cleaned)
        return val.quantize(Decimal("0.01"))
    except InvalidOperation:
        return None


def extract_deterministic_facts(
    claim_id: str,
    chunks: Sequence[EvidenceChunk],
) -> list[ExtractedFact]:
    """Extract verifiable facts directly from chunks with exact provenance citations.

    Prompt-injection lines are stripped prior to extraction so adversarial directives
    cannot forge claimed amounts or policy facts.
    """
    facts: list[ExtractedFact] = []
    seen_keys: set[tuple[str, str, str]] = set()

    def add_fact(
        field_name: str,
        value: str,
        normalized: str | None,
        chunk: EvidenceChunk,
        quote: str,
        confidence: float = 0.96,
    ) -> None:
        dedupe_key = (field_name, normalized or value.lower(), chunk.document_id)
        if dedupe_key in seen_keys:
            return
        seen_keys.add(dedupe_key)
        facts.append(
            ExtractedFact(
                claim_id=claim_id,
                field_name=field_name,
                value=value.strip(),
                normalized_value=normalized,
                classification=FactClassification.EXTRACTED_FACT,
                confidence=confidence,
                source_method="deterministic",
                evidence_refs=[_make_ref(chunk, quote)],
            )
        )

    for chunk in chunks:
        safe_text = strip_injection_lines(chunk.text)
        if not safe_text:
            continue

        for m in _POLICY_NUM_RE.finditer(safe_text):
            val = m.group(1).strip().upper()
            add_fact("policy_number", val, val, chunk, m.group(0))

        for m in _CLAIMANT_RE.finditer(safe_text):
            val = m.group(1).strip()
            norm = re.sub(r"\s+", " ", val.lower()).strip(".")
            add_fact("claimant_name", val, norm, chunk, m.group(0))

        for m in _INCIDENT_DATE_RE.finditer(safe_text):
            raw_d = m.group(1).strip()
            parsed = parse_iso_or_us_date(raw_d)
            add_fact(
                "incident_date",
                raw_d,
                parsed.isoformat() if parsed else raw_d,
                chunk,
                m.group(0),
            )

        for m in _EFFECTIVE_PERIOD_RE.finditer(safe_text):
            start_s, end_s = m.group(1), m.group(2)
            add_fact("policy_effective_start", start_s, start_s, chunk, m.group(0))
            add_fact("policy_effective_end", end_s, end_s, chunk, m.group(0))

        for m in _EFFECTIVE_START_RE.finditer(safe_text):
            add_fact("policy_effective_start", m.group(1), m.group(1), chunk, m.group(0))

        for m in _EFFECTIVE_END_RE.finditer(safe_text):
            add_fact("policy_effective_end", m.group(1), m.group(1), chunk, m.group(0))

        for m in _CLAIMED_AMT_RE.finditer(safe_text):
            amt = parse_decimal_amount(m.group(1))
            if amt is not None:
                add_fact("claimed_amount", f"${amt:,.2f}", str(amt), chunk, m.group(0))

        for m in _INVOICE_AMT_RE.finditer(safe_text):
            amt = parse_decimal_amount(m.group(1))
            if amt is not None:
                add_fact("invoice_amount", f"${amt:,.2f}", str(amt), chunk, m.group(0))

        for m in _DEDUCTIBLE_RE.finditer(safe_text):
            amt = parse_decimal_amount(m.group(1))
            if amt is not None:
                add_fact("deductible", f"${amt:,.2f}", str(amt), chunk, m.group(0))

        for m in _COVERAGE_LIMIT_RE.finditer(safe_text):
            amt = parse_decimal_amount(m.group(1))
            if amt is not None:
                add_fact("coverage_limit", f"${amt:,.2f}", str(amt), chunk, m.group(0))

        for m in _VIN_RE.finditer(safe_text):
            val = m.group(1).strip().upper()
            add_fact("asset_id", val, val, chunk, m.group(0))

        for m in _VENDOR_RE.finditer(safe_text):
            val = m.group(1).strip()
            add_fact("vendor_name", val, val.lower(), chunk, m.group(0))

        for m in _INCIDENT_TYPE_RE.finditer(safe_text):
            val = m.group(1).strip()
            add_fact("incident_type", val, val.lower(), chunk, m.group(0))

        for m in _COVERED_PERILS_RE.finditer(safe_text):
            val = m.group(1).strip()
            add_fact("covered_perils", val, val.lower(), chunk, m.group(0))

        for m in _EXCLUSIONS_RE.finditer(safe_text):
            val = m.group(1).strip()
            add_fact("exclusions", val, val.lower(), chunk, m.group(0))

    return facts


# --- Cross-Document Entity Resolution ---

_ENTITY_FIELD_MAP: dict[str, str] = {
    "claimant_name": "claimant_name",
    "policy_number": "policy_number",
    "incident_date": "incident_date",
    "asset_id": "asset_id",
    "vendor_name": "vendor_name",
    "claimed_amount": "monetary_amount",
    "invoice_amount": "monetary_amount",
}


def resolve_entities(
    claim_id: str,
    facts: Sequence[ExtractedFact],
) -> list[EntityLink]:
    """Link and reconcile entities across claim documents and detect cross-document conflicts."""
    grouped: dict[str, list[ExtractedFact]] = defaultdict(list)
    for fact in facts:
        entity_type = _ENTITY_FIELD_MAP.get(fact.field_name)
        if entity_type is not None:
            grouped[entity_type].append(fact)

    links: list[EntityLink] = []
    for entity_type in sorted(grouped):
        group_facts = grouped[entity_type]
        observed_map: dict[str, str] = {}
        doc_ids: list[str] = []
        refs: list[EvidenceRef] = []

        for f in group_facts:
            norm = (f.normalized_value or f.value).strip().lower()
            if norm not in observed_map:
                observed_map[norm] = f.value
            for ref in f.evidence_refs:
                if ref.document_id not in doc_ids:
                    doc_ids.append(ref.document_id)
                refs.append(ref)

        unique_norms = list(observed_map.keys())
        unique_display = list(observed_map.values())
        canonical = unique_display[0]
        has_conflict = False
        conflict_detail: str | None = None

        if entity_type == "monetary_amount":
            # Compare claimed_amount vs invoice_amount specifically
            claimed_vals = {
                f.normalized_value for f in group_facts if f.field_name == "claimed_amount"
            }
            invoice_vals = {
                f.normalized_value for f in group_facts if f.field_name == "invoice_amount"
            }
            if claimed_vals and invoice_vals and claimed_vals != invoice_vals:
                has_conflict = True
                conflict_detail = (
                    f"Claimed amount ({', '.join(sorted(v or '' for v in claimed_vals))}) "
                    f"differs from invoice/estimate amount "
                    f"({', '.join(sorted(v or '' for v in invoice_vals))})."
                )
        elif len(unique_norms) > 1:
            has_conflict = True
            conflict_detail = (
                f"Conflicting {entity_type} values across documents: "
                f"{', '.join(unique_display)}."
            )

        links.append(
            EntityLink(
                claim_id=claim_id,
                entity_type=entity_type,
                canonical_value=canonical,
                observed_values=unique_display,
                document_ids=doc_ids,
                has_conflict=has_conflict,
                conflict_detail=conflict_detail,
                evidence_refs=refs[:6],
            )
        )

    return links


# --- Policy Coverage Lookup ---


def _split_list_field(raw: str) -> list[str]:
    return [part.strip().lower() for part in re.split(r"[,;/]+", raw) if part.strip()]


def evaluate_policy_coverage(
    claim_id: str,
    facts: Sequence[ExtractedFact],
) -> PolicyCoverageResult:
    """Deterministically evaluate policy coverage against extracted policy schedule facts."""
    by_field: dict[str, list[ExtractedFact]] = defaultdict(list)
    for f in facts:
        by_field[f.field_name].append(f)

    policy_number = (
        by_field["policy_number"][0].value if by_field.get("policy_number") else None
    )
    eff_start = (
        parse_iso_or_us_date(by_field["policy_effective_start"][0].normalized_value or "")
        if by_field.get("policy_effective_start")
        else None
    )
    eff_end = (
        parse_iso_or_us_date(by_field["policy_effective_end"][0].normalized_value or "")
        if by_field.get("policy_effective_end")
        else None
    )
    incident_dt = (
        parse_iso_or_us_date(by_field["incident_date"][0].normalized_value or "")
        if by_field.get("incident_date")
        else None
    )
    deductible = (
        parse_decimal_amount(by_field["deductible"][0].normalized_value or "")
        if by_field.get("deductible")
        else None
    )
    coverage_limit = (
        parse_decimal_amount(by_field["coverage_limit"][0].normalized_value or "")
        if by_field.get("coverage_limit")
        else None
    )
    claimed_amount = (
        parse_decimal_amount(by_field["claimed_amount"][0].normalized_value or "")
        if by_field.get("claimed_amount")
        else None
    )
    if claimed_amount is None and by_field.get("invoice_amount"):
        claimed_amount = parse_decimal_amount(by_field["invoice_amount"][0].normalized_value or "")

    covered_perils: list[str] = []
    for f in by_field.get("covered_perils", []):
        covered_perils.extend(_split_list_field(f.value))
    covered_perils = sorted(set(covered_perils))

    exclusions: list[str] = []
    for f in by_field.get("exclusions", []):
        exclusions.extend(_split_list_field(f.value))
    exclusions = sorted(set(exclusions))

    incident_types = [f.value.lower() for f in by_field.get("incident_type", [])]

    refs: list[EvidenceRef] = []
    for key in (
        "policy_number",
        "policy_effective_start",
        "policy_effective_end",
        "deductible",
        "coverage_limit",
        "covered_perils",
        "exclusions",
        "incident_date",
    ):
        for f in by_field.get(key, []):
            refs.extend(f.evidence_refs)

    reasons: list[str] = []

    if policy_number is None or (eff_start is None and coverage_limit is None):
        reasons.append("Policy schedule details are incomplete or missing from uploaded documents.")
        return PolicyCoverageResult(
            claim_id=claim_id,
            policy_number=policy_number,
            verdict=CoverageVerdict.UNVERIFIED,
            policy_effective_start=eff_start,
            policy_effective_end=eff_end,
            incident_date=incident_dt,
            deductible=deductible,
            coverage_limit=coverage_limit,
            claimed_amount=claimed_amount,
            covered_perils=covered_perils,
            exclusions=exclusions,
            reasons=reasons,
            evidence_refs=refs[:8],
        )

    # Check 1: Incident date within effective policy period
    if incident_dt and eff_start and eff_end and not (eff_start <= incident_dt <= eff_end):
        reasons.append(
            f"Incident date {incident_dt.isoformat()} is outside active policy period "
            f"{eff_start.isoformat()} to {eff_end.isoformat()}."
        )
        return PolicyCoverageResult(
            claim_id=claim_id,
            policy_number=policy_number,
            verdict=CoverageVerdict.EXCLUDED,
            policy_effective_start=eff_start,
            policy_effective_end=eff_end,
            incident_date=incident_dt,
            deductible=deductible,
            coverage_limit=coverage_limit,
            claimed_amount=claimed_amount,
            estimated_payable=Decimal("0.00"),
            covered_perils=covered_perils,
            exclusions=exclusions,
            reasons=reasons,
            evidence_refs=refs[:8],
        )

    # Check 2: Explicit exclusion match
    for inc_t in incident_types:
        for excl in exclusions:
            if excl in inc_t or inc_t in excl:
                reasons.append(
                    f"Incident peril '{inc_t}' matches policy exclusion '{excl}'."
                )
                return PolicyCoverageResult(
                    claim_id=claim_id,
                    policy_number=policy_number,
                    verdict=CoverageVerdict.EXCLUDED,
                    policy_effective_start=eff_start,
                    policy_effective_end=eff_end,
                    incident_date=incident_dt,
                    deductible=deductible,
                    coverage_limit=coverage_limit,
                    claimed_amount=claimed_amount,
                    estimated_payable=Decimal("0.00"),
                    covered_perils=covered_perils,
                    exclusions=exclusions,
                    reasons=reasons,
                    evidence_refs=refs[:8],
                )

    # Check 3: Covered peril verification
    peril_matched = True
    if incident_types and covered_perils:
        peril_matched = any(
            any(p in inc_t or inc_t in p for p in covered_perils) for inc_t in incident_types
        )
        if not peril_matched:
            reasons.append(
                f"Incident type '{incident_types[0]}' is not listed among covered perils "
                f"({', '.join(covered_perils)})."
            )
            return PolicyCoverageResult(
                claim_id=claim_id,
                policy_number=policy_number,
                verdict=CoverageVerdict.EXCLUDED,
                policy_effective_start=eff_start,
                policy_effective_end=eff_end,
                incident_date=incident_dt,
                deductible=deductible,
                coverage_limit=coverage_limit,
                claimed_amount=claimed_amount,
                estimated_payable=Decimal("0.00"),
                covered_perils=covered_perils,
                exclusions=exclusions,
                reasons=reasons,
                evidence_refs=refs[:8],
            )

    # Check 4: Deductible and coverage limit math
    verdict = CoverageVerdict.COVERED
    estimated_payable: Decimal | None = None
    if claimed_amount is not None:
        ded = deductible or Decimal("0.00")
        after_deductible = max(Decimal("0.00"), claimed_amount - ded)
        if coverage_limit is not None and after_deductible > coverage_limit:
            estimated_payable = coverage_limit
            verdict = CoverageVerdict.PARTIAL
            reasons.append(
                f"Claimed net amount (${after_deductible:,.2f}) exceeds coverage limit "
                f"(${coverage_limit:,.2f}); payable capped at policy limit."
            )
        else:
            estimated_payable = after_deductible
            reasons.append(
                f"Incident falls within active policy period and covered perils; "
                f"estimated payable after ${ded:,.2f} deductible is ${estimated_payable:,.2f}."
            )
    else:
        reasons.append("Policy is active for incident date; claimed amount pending verification.")

    return PolicyCoverageResult(
        claim_id=claim_id,
        policy_number=policy_number,
        verdict=verdict,
        policy_effective_start=eff_start,
        policy_effective_end=eff_end,
        incident_date=incident_dt,
        deductible=deductible,
        coverage_limit=coverage_limit,
        claimed_amount=claimed_amount,
        estimated_payable=estimated_payable,
        covered_perils=covered_perils,
        exclusions=exclusions,
        reasons=reasons,
        evidence_refs=refs[:8],
    )


# --- Inconsistency & Missing Evidence Detection ---

POLICE_REPORT_THRESHOLD_USD = Decimal("5000.00")
POLICE_REQUIRED_PERILS = ("theft", "stolen", "collision", "hit and run", "vandalism", "burglary")


def detect_inconsistencies_and_missing_evidence(
    *,
    claim_id: str,
    documents: Sequence[DocumentRecord],
    chunks: Sequence[EvidenceChunk],
    facts: Sequence[ExtractedFact],
    entity_links: Sequence[EntityLink],
    coverage: PolicyCoverageResult,
) -> tuple[list[InconsistencyFinding], list[MissingEvidenceItem]]:
    """Run deterministic cross-document consistency checks and missing-evidence rules."""
    findings: list[InconsistencyFinding] = []
    missing: list[MissingEvidenceItem] = []

    by_field: dict[str, list[ExtractedFact]] = defaultdict(list)
    for f in facts:
        by_field[f.field_name].append(f)

    # 1. Claimed amount vs invoice/estimate total mismatch
    claimed_facts = by_field.get("claimed_amount", [])
    invoice_facts = by_field.get("invoice_amount", [])
    if claimed_facts and invoice_facts:
        claimed_amt = parse_decimal_amount(claimed_facts[0].normalized_value or "")
        invoice_amts = [
            amt
            for f in invoice_facts
            if (amt := parse_decimal_amount(f.normalized_value or "")) is not None
        ]
        if claimed_amt is not None and invoice_amts:
            # Check if either individual invoice or sum of invoices matches claimed amount
            total_invoiced = sum(invoice_amts, Decimal("0.00"))
            best_diff = min(
                abs(claimed_amt - total_invoiced),
                *(abs(claimed_amt - a) for a in invoice_amts),
            )
            if best_diff > Decimal("1.00"):
                pct_diff = (best_diff / max(claimed_amt, Decimal("1.00"))) * Decimal("100")
                sev = (
                    Severity.HIGH
                    if pct_diff >= Decimal("15") or best_diff >= Decimal("1000")
                    else Severity.MEDIUM
                )
                refs = [*claimed_facts[0].evidence_refs, *invoice_facts[0].evidence_refs]
                findings.append(
                    InconsistencyFinding(
                        claim_id=claim_id,
                        code="AMOUNT_MISMATCH",
                        severity=sev,
                        title="Claimed amount does not match invoice/estimate total",
                        description=(
                            f"Claimed amount is ${claimed_amt:,.2f}, while supporting "
                            f"invoice/estimate total is ${total_invoiced:,.2f} "
                            f"(discrepancy of ${best_diff:,.2f}, {pct_diff:.1f}%)."
                        ),
                        evidence_refs=refs,
                    )
                )

    # 2. Incident date outside policy period
    if (
        coverage.incident_date
        and coverage.policy_effective_start
        and coverage.policy_effective_end
        and not (
            coverage.policy_effective_start
            <= coverage.incident_date
            <= coverage.policy_effective_end
        )
    ):
        findings.append(
            InconsistencyFinding(
                claim_id=claim_id,
                code="INCIDENT_OUTSIDE_POLICY_PERIOD",
                severity=Severity.HIGH,
                title="Incident date falls outside policy coverage period",
                description=(
                    f"Incident date {coverage.incident_date.isoformat()} falls outside "
                    f"policy period {coverage.policy_effective_start.isoformat()} to "
                    f"{coverage.policy_effective_end.isoformat()}."
                ),
                evidence_refs=coverage.evidence_refs[:4],
            )
        )

    # 3. Cross-document entity conflicts (claimant name, policy number, incident date, asset ID)
    for link in entity_links:
        if not link.has_conflict:
            continue
        if link.entity_type == "claimant_name":
            findings.append(
                InconsistencyFinding(
                    claim_id=claim_id,
                    code="CLAIMANT_NAME_MISMATCH",
                    severity=Severity.MEDIUM,
                    title="Claimant name differs across claim documents",
                    description=link.conflict_detail or "Multiple claimant names observed.",
                    evidence_refs=link.evidence_refs[:4],
                )
            )
        elif link.entity_type == "policy_number":
            findings.append(
                InconsistencyFinding(
                    claim_id=claim_id,
                    code="POLICY_NUMBER_MISMATCH",
                    severity=Severity.HIGH,
                    title="Policy number differs across claim documents",
                    description=link.conflict_detail or "Multiple policy numbers observed.",
                    evidence_refs=link.evidence_refs[:4],
                )
            )
        elif link.entity_type == "incident_date":
            findings.append(
                InconsistencyFinding(
                    claim_id=claim_id,
                    code="INCIDENT_DATE_MISMATCH",
                    severity=Severity.HIGH,
                    title="Incident date differs across claim documents",
                    description=link.conflict_detail or "Multiple incident dates observed.",
                    evidence_refs=link.evidence_refs[:4],
                )
            )

    # 4. Prompt-injection attempt embedded in uploaded claim document
    injection_report = detect_prompt_injection_in_chunks(chunks)
    if injection_report.detected:
        inj_refs: list[EvidenceRef] = []
        chunk_by_id = {c.chunk_id: c for c in chunks}
        for inf in injection_report.findings[:4]:
            if inf.chunk_id and inf.chunk_id in chunk_by_id:
                inj_refs.append(_make_ref(chunk_by_id[inf.chunk_id], inf.matched_excerpt))
        findings.append(
            InconsistencyFinding(
                claim_id=claim_id,
                code="PROMPT_INJECTION_DETECTED",
                severity=Severity.HIGH,
                title="Adversarial instruction override detected in claim document",
                description=(
                    f"Detected {len(injection_report.findings)} prompt-injection/approval-forcing "
                    f"pattern(s) in uploaded document text. Content was isolated from AI prompts."
                ),
                evidence_refs=inj_refs,
            )
        )

    # --- Missing Evidence Checks ---
    doc_types_present = {d.document_type for d in documents}

    if DocumentType.POLICY_SCHEDULE not in doc_types_present:
        missing.append(
            MissingEvidenceItem(
                claim_id=claim_id,
                code="MISSING_POLICY_SCHEDULE",
                required_document_type=DocumentType.POLICY_SCHEDULE,
                severity=Severity.MEDIUM,
                reason="Policy schedule is required to verify active dates, limits, and perils.",
            )
        )

    if (
        DocumentType.INVOICE not in doc_types_present
        and DocumentType.REPAIR_ESTIMATE not in doc_types_present
    ):
        missing.append(
            MissingEvidenceItem(
                claim_id=claim_id,
                code="MISSING_ITEMIZED_INVOICE",
                required_document_type=DocumentType.INVOICE,
                severity=Severity.HIGH,
                reason="An itemized invoice or repair estimate is required to substantiate loss.",
            )
        )

    incident_types_lower = " ".join(f.value.lower() for f in by_field.get("incident_type", []))
    claimed_amt_val = coverage.claimed_amount or Decimal("0.00")
    requires_police = any(p in incident_types_lower for p in POLICE_REQUIRED_PERILS) and (
        claimed_amt_val >= POLICE_REPORT_THRESHOLD_USD
    )
    if requires_police and DocumentType.POLICE_REPORT not in doc_types_present:
        missing.append(
            MissingEvidenceItem(
                claim_id=claim_id,
                code="MISSING_POLICE_REPORT",
                required_document_type=DocumentType.POLICE_REPORT,
                severity=Severity.HIGH,
                reason=(
                    f"Police report is mandatory for '{incident_types_lower}' claims "
                    f"at or above ${POLICE_REPORT_THRESHOLD_USD:,.2f}."
                ),
            )
        )

    return findings, missing


# --- Deterministic Weighted Risk Assessment ---


def assess_claim_risk(
    *,
    claim_id: str,
    coverage: PolicyCoverageResult,
    inconsistencies: Sequence[InconsistencyFinding],
    missing_evidence: Sequence[MissingEvidenceItem],
) -> RiskAssessment:
    """Compute a transparent, deterministic weighted risk score (0..100).

    This risk assessment is strictly ADVISORY for human reviewers — it never approves
    or rejects a claim autonomously.
    """
    finding_by_code: dict[str, InconsistencyFinding] = {f.code: f for f in inconsistencies}
    missing_codes: set[str] = {m.code for m in missing_evidence}

    indicator_specs: list[tuple[str, str, int, bool, str, list[EvidenceRef]]] = [
        (
            "OUTSIDE_POLICY_PERIOD",
            "Incident outside policy coverage period",
            30,
            "INCIDENT_OUTSIDE_POLICY_PERIOD" in finding_by_code,
            (
                finding_by_code["INCIDENT_OUTSIDE_POLICY_PERIOD"].description
                if "INCIDENT_OUTSIDE_POLICY_PERIOD" in finding_by_code
                else "Incident date is within verified policy coverage period."
            ),
            (
                finding_by_code["INCIDENT_OUTSIDE_POLICY_PERIOD"].evidence_refs
                if "INCIDENT_OUTSIDE_POLICY_PERIOD" in finding_by_code
                else []
            ),
        ),
        (
            "AMOUNT_MISMATCH",
            "Invoice vs claimed amount discrepancy",
            25,
            "AMOUNT_MISMATCH" in finding_by_code,
            (
                finding_by_code["AMOUNT_MISMATCH"].description
                if "AMOUNT_MISMATCH" in finding_by_code
                else "Claimed amount matches supporting invoice/estimate totals."
            ),
            (
                finding_by_code["AMOUNT_MISMATCH"].evidence_refs
                if "AMOUNT_MISMATCH" in finding_by_code
                else []
            ),
        ),
        (
            "PROMPT_INJECTION_TAMPERING",
            "Adversarial prompt-injection payload in document",
            25,
            "PROMPT_INJECTION_DETECTED" in finding_by_code,
            (
                finding_by_code["PROMPT_INJECTION_DETECTED"].description
                if "PROMPT_INJECTION_DETECTED" in finding_by_code
                else "No prompt-injection directives detected in uploaded documents."
            ),
            (
                finding_by_code["PROMPT_INJECTION_DETECTED"].evidence_refs
                if "PROMPT_INJECTION_DETECTED" in finding_by_code
                else []
            ),
        ),
        (
            "IDENTITY_OR_ENTITY_CONFLICT",
            "Cross-document claimant/policy/date conflict",
            15,
            any(
                code in finding_by_code
                for code in (
                    "CLAIMANT_NAME_MISMATCH",
                    "POLICY_NUMBER_MISMATCH",
                    "INCIDENT_DATE_MISMATCH",
                )
            ),
            (
                "; ".join(
                    finding_by_code[c].description
                    for c in (
                        "CLAIMANT_NAME_MISMATCH",
                        "POLICY_NUMBER_MISMATCH",
                        "INCIDENT_DATE_MISMATCH",
                    )
                    if c in finding_by_code
                )
                or "Claimant, policy number, and incident dates are consistent across documents."
            ),
            [
                ref
                for c in (
                    "CLAIMANT_NAME_MISMATCH",
                    "POLICY_NUMBER_MISMATCH",
                    "INCIDENT_DATE_MISMATCH",
                )
                if c in finding_by_code
                for ref in finding_by_code[c].evidence_refs[:2]
            ],
        ),
        (
            "MISSING_CRITICAL_EVIDENCE",
            "Missing required corroborating evidence",
            15,
            bool(missing_codes & {"MISSING_POLICE_REPORT", "MISSING_ITEMIZED_INVOICE"}),
            (
                f"Missing critical evidence: {', '.join(sorted(missing_codes))}."
                if missing_codes & {"MISSING_POLICE_REPORT", "MISSING_ITEMIZED_INVOICE"}
                else "Core supporting documents (invoice and required reports) are present."
            ),
            [],
        ),
        (
            "COVERAGE_EXCLUDED_OR_UNVERIFIED",
            "Coverage excluded or unverified",
            10,
            coverage.verdict in {CoverageVerdict.EXCLUDED, CoverageVerdict.UNVERIFIED},
            (
                f"Policy coverage verdict is {coverage.verdict.value}: "
                f"{'; '.join(coverage.reasons)}"
                if coverage.verdict in {CoverageVerdict.EXCLUDED, CoverageVerdict.UNVERIFIED}
                else f"Policy coverage verdict is {coverage.verdict.value}."
            ),
            coverage.evidence_refs[:2]
            if coverage.verdict in {CoverageVerdict.EXCLUDED, CoverageVerdict.UNVERIFIED}
            else [],
        ),
    ]

    indicators: list[RiskIndicator] = []
    raw_score = 0
    for code, label, weight, triggered, explanation, refs in indicator_specs:
        contrib = weight if triggered else 0
        raw_score += contrib
        indicators.append(
            RiskIndicator(
                code=code,
                label=label,
                weight=weight,
                triggered=triggered,
                score_contribution=contrib,
                explanation=explanation,
                evidence_refs=refs,
            )
        )

    total_score = min(100, raw_score)
    if total_score >= 65:
        severity = Severity.HIGH
    elif total_score >= 30:
        severity = Severity.MEDIUM
    else:
        severity = Severity.LOW

    triggered_labels = [ind.label for ind in indicators if ind.triggered]
    if triggered_labels:
        summary = (
            f"Advisory risk score {total_score}/100 ({severity.value}). "
            f"Triggered indicators: {'; '.join(triggered_labels)}. "
            f"Human reviewer judgment is required."
        )
    else:
        summary = (
            f"Advisory risk score {total_score}/100 ({severity.value}). "
            f"No deterministic risk indicators triggered; ready for standard human review."
        )

    return RiskAssessment(
        claim_id=claim_id,
        total_score=total_score,
        severity=severity,
        indicators=indicators,
        summary=summary,
        advisory_only=True,
    )
