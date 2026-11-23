#!/usr/bin/env python3
"""Verify source-derived examples, JSON Schemas, OpenAPI structure and coverage.

Requires docs/contracts/requirements.txt. Does not call services, SQS or S3.
"""
import copy
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlparse

from jsonschema import Draft7Validator, Draft202012Validator, FormatChecker
from openapi_spec_validator import validate as validate_openapi
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parent
DOCS = ROOT.parent
SCHEMAS = (ROOT / "schemas").resolve()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def retrieve(uri):
    parsed = urlparse(uri)
    if parsed.scheme != "file":
        raise ValueError(f"External schema reference is not allowed: {uri}")
    path = Path(unquote(parsed.path)).resolve()
    if not path.is_relative_to(SCHEMAS):
        raise ValueError(f"Schema reference is outside contract directory: {uri}")
    return Resource.from_contents(read(path))


REGISTRY = Registry(retrieve=retrieve)


def validator(path):
    schema = read(path)
    Draft7Validator.check_schema(schema)
    schema["$id"] = path.resolve().as_uri()
    return Draft7Validator(schema, registry=REGISTRY, format_checker=FormatChecker())


def validate_instance(path, instance, label):
    errors = list(validator(path).iter_errors(instance))
    if errors:
        details = "; ".join(f"{list(e.absolute_path)}: {e.message}" for e in errors[:3])
        raise AssertionError(f"{label}: {details}")


def expect_rejected(path, instance, label):
    if not list(validator(path).iter_errors(instance)):
        raise AssertionError(f"Expected invalid instance to be rejected: {label}")


def get_section(source, heading):
    found = re.search(r"^### " + re.escape(heading) + r".*$", source, re.M)
    if not found:
        raise AssertionError(f"Source section not found: {heading}")
    rest = source[found.end() :]
    next_heading = re.search(r"^### ", rest, re.M)
    return rest[: next_heading.start()] if next_heading else rest


def source_examples(source, heading):
    result = []
    for block in re.findall(r"```json\s*\n(.*?)\n```", get_section(source, heading), re.S):
        try:
            result.append(json.loads(block))
        except json.JSONDecodeError:
            continue
    return result


def main():
    inventory = read(ROOT / "inventory.json")
    source = (DOCS / "service-message-contracts.md").read_text(encoding="utf-8")
    spec = (DOCS / "spec-decision-log.md").read_text(encoding="utf-8")
    primary = {
        "collection.schedule.triggered": ("7.1", 1),
        "analysis.execute": ("6.1", 0),
        "analysis.completed": ("6.2", 0),
        "collection.execute": ("7.2", 0),
        "collection.completed": ("7.3", 0),
        "schema.prepare": ("18.1", 0),
        "schema.prepared": ("18.1", 1),
        "ingestion.execute": ("8.1", 0),
        "ingestion.completed": ("8.2", 0),
        "job.issue.detected": ("9.1", 0),
        "dataset.apply": ("10.1", 0),
        "dataset.applied": ("10.2", 0),
        "project-data.delete": ("16.4", 0),
        "project-data.deleted": ("16.4", 1),
    }
    variants = {
        "scheduler-template": ("7.1", 0, "scheduler-template"),
        "analysis-failed": ("16.2", 0, "analysis.completed"),
        "ingestion-failed": ("16.2", 1, "ingestion.completed"),
        "collection-empty-list": ("16.3", 1, "job.issue.detected"),
        "dataset-update-sources": ("10.3", 0, "dataset.apply"),
        "dataset-update-sources-result": ("10.4", 0, "dataset.applied"),
        "dataset-begin-change": ("10.5", 0, "dataset.apply"),
        "dataset-end-change": ("10.5", 1, "dataset.apply"),
        "dataset-end-result": ("10.5", 2, "dataset.applied"),
        "dataset-set-connector-active": ("10.6", 0, "dataset.apply"),
        "dataset-set-connector-active-result": ("10.6", 1, "dataset.applied"),
        "dataset-update-metadata": ("19.4", 0, "dataset.apply"),
        "dataset-update-metadata-result": ("19.4", 1, "dataset.applied"),
        "dataset-set-connector-change": ("19.8", 0, "dataset.apply"),
        "project-data-delete-failed": ("16.4", 2, "project-data.deleted"),
    }
    schema_count = 0
    for path in SCHEMAS.rglob("*.json"):
        Draft7Validator.check_schema(read(path))
        schema_count += 1
    for name, (section, index) in primary.items():
        path = ROOT / "examples/messages" / f"{name}.json"
        example = read(path)
        if example != source_examples(source, section)[index]:
            raise AssertionError(f"Original source example changed: {name}")
        validate_instance(SCHEMAS / "messages" / f"{name}.schema.json", example, name)
    command_template = source_examples(source, "3.1")[0]
    event_template = source_examples(source, "3.2")[0]
    event_names = {"analysis.completed", "collection.completed", "schema.prepared", "ingestion.completed", "job.issue.detected", "dataset.applied", "project-data.deleted"}
    for name in primary:
        if name == "collection.schedule.triggered":
            continue
        is_event = name in event_names
        wrapper = copy.deepcopy(event_template if is_event else command_template)
        wrapper["event_type" if is_event else "command_type"] = name
        wrapper["payload"] = read(ROOT / "examples/messages" / f"{name}.json")
        validate_instance(SCHEMAS / "messages" / f"{name}.envelope.schema.json", wrapper, name + " envelope")

    for name, (section, index, schema_name) in variants.items():
        path = ROOT / "examples/messages" / f"{name}.json"
        example = read(path)
        if example != source_examples(source, section)[index]:
            raise AssertionError(f"Original source example changed: {name}")
        validate_instance(SCHEMAS / "messages" / f"{schema_name}.schema.json", example, name)

    analysis = SCHEMAS / "messages/analysis.execute.schema.json"
    source_analysis = read(ROOT / "examples/messages/analysis.execute.json")
    case = copy.deepcopy(source_analysis)
    case.pop("collection_purpose")
    expect_rejected(analysis, case, "INITIAL collection purpose missing")
    case = copy.deepcopy(source_analysis)
    case["max_duration_seconds"] = 301
    expect_rejected(analysis, case, "INITIAL analysis exceeds 300 seconds")
    case = copy.deepcopy(source_analysis)
    case["analysis_id"] = "not-uuid"
    expect_rejected(analysis, case, "invalid analysis UUID")

    analysis_result = SCHEMAS / "messages/analysis.completed.schema.json"
    case = read(ROOT / "examples/messages/analysis.completed.json")
    case["source_results"][0]["sample_ref"] = None
    expect_rejected(analysis_result, case, "PASSED without sample reference")
    case = read(ROOT / "examples/messages/analysis.completed.json")
    case["source_results"][0]["intermediate_ref"] = None
    expect_rejected(analysis_result, case, "INITIAL success without intermediate reference")

    source_analysis_result = read(ROOT / "examples/messages/analysis.completed.json")
    case = copy.deepcopy(source_analysis_result)
    case.pop("recommendation_sample_count")
    validate_instance(analysis_result, case, "legacy analysis result without recommendation count")
    case = copy.deepcopy(source_analysis_result)
    case["recommendation_sample_count"] = 51
    expect_rejected(analysis_result, case, "recommendation count exceeds 50")
    case = copy.deepcopy(source_analysis_result)
    case["purpose"] = "REVALIDATE"
    expect_rejected(analysis_result, case, "revalidation cannot set a recommendation count")

    collect = SCHEMAS / "messages/collection.execute.schema.json"
    source_collect = read(ROOT / "examples/messages/collection.execute.json")
    case = copy.deepcopy(source_collect)
    case["projects"][0]["latest_records"] = []
    expect_rejected(collect, case, "INCREMENTAL with no baseline")
    case = copy.deepcopy(source_collect)
    case["projects"][0]["collection_mode"] = "FULL"
    expect_rejected(collect, case, "FULL with baseline")
    case["projects"][0]["latest_records"] = []
    case["max_duration_seconds"] = 300
    validate_instance(collect, case, "FULL 300-second budget")
    case = copy.deepcopy(source_collect)
    case["max_duration_seconds"] = 61
    expect_rejected(collect, case, "INCREMENTAL exceeds 60 seconds")
    case = copy.deepcopy(source_collect)
    case["projects"][0]["field_connectors"][0]["selector"] = 123
    expect_rejected(collect, case, "selector must be a string")
    case = copy.deepcopy(source_collect)
    case["navigation"]["pagination"] = {"type": "URL_TEMPLATE", "url_template": "https://example.com/{value}", "start": 1, "step": "1"}
    expect_rejected(collect, case, "step must be an integer")
    case = copy.deepcopy(source_collect)
    case["navigation"]["pagination"] = {"type": "CLICK_NEXT", "selector": ".next", "wait_selector": ".list"}
    expect_rejected(collect, case, "HTTP with browser-only navigation")
    case = read(ROOT / "examples/messages/scheduler-template.json")
    expect_rejected(SCHEMAS / "messages/collection.schedule.triggered.schema.json", case, "scheduler template is not a delivered event")

    # 새로 구체화한 교환 조건과 기존 예시가 양립하는지 확인한다.
    case = copy.deepcopy(source_collect)
    case["projects"][0]["field_connectors"][0]["extract"] = {"type": "ATTRIBUTE", "attribute": "href"}
    validate_instance(collect, case, "ATTRIBUTE with attribute name")
    case["projects"][0]["field_connectors"][0]["extract"]["attribute"] = None
    expect_rejected(collect, case, "ATTRIBUTE without attribute name")
    case["projects"][0]["field_connectors"][0]["extract"] = {"type": "TEXT", "attribute": "href"}
    expect_rejected(collect, case, "TEXT with attribute name")
    case["projects"][0]["field_connectors"][0]["extract"] = {"type": "HTML", "attribute": None}
    expect_rejected(collect, case, "unsupported extraction mode")

    revalidate = copy.deepcopy(source_analysis)
    revalidate["purpose"] = "REVALIDATE"
    revalidate["current_candidate"] = read(ROOT / "examples/messages/analysis.completed.json")["candidate"]
    validate_instance(analysis, revalidate, "REVALIDATE candidate and sources")
    case = copy.deepcopy(revalidate)
    case["current_candidate"] = None
    expect_rejected(analysis, case, "REVALIDATE without candidate")
    recheck = copy.deepcopy(revalidate)
    recheck["purpose"] = "RECHECK_SOURCE"
    recheck["failure_context"] = {
        "source_job_id": "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc", "scope": "PROJECT_SOURCE",
        "source_id": "1c226765-48b1-4518-ad16-057e8998eb84",
        "project_id": recheck["project_id"], "project_source_id": recheck["sources"][0]["project_source_id"],
        "field_ids": [], "error": {"code": "EMPTY_EXTRACTION", "message": "0건", "details": {}},
    }
    validate_instance(analysis, recheck, "RECHECK_SOURCE with stage omitted")

    for purpose, scope in (("REPAIR_NAVIGATION", "SOURCE"), ("REPAIR_CONNECTOR", "PROJECT_SOURCE")):
        repair = copy.deepcopy(recheck)
        repair["purpose"] = purpose
        candidate = repair["current_candidate"]
        config = candidate["source_configs"][0]
        repair["recovery_baselines"] = [{
            "project_id": repair["project_id"],
            "project_source_id": config["project_source_id"],
            "schema_version": 1,
            "connector_revision": config["connector_revision"],
            "latest_records": [],
            "approved_sample_ref": None,
            "schema": candidate["schema"],
            "field_connectors": config["field_connectors"],
        }]
        failure = repair["failure_context"]
        failure["scope"] = scope
        if scope == "SOURCE":
            repair["project_id"] = None
            candidate["schema"] = None
            failure["project_id"] = None
            failure["project_source_id"] = None

        validate_instance(analysis, repair, f"{purpose} with existing list rule")
        case = copy.deepcopy(repair)
        case.pop("current_candidate")
        expect_rejected(analysis, case, f"{purpose} without current candidate")
        case["current_candidate"] = None
        expect_rejected(analysis, case, f"{purpose} with null candidate")
        case["current_candidate"] = copy.deepcopy(candidate)
        case["current_candidate"]["source_configs"] = []
        expect_rejected(analysis, case, f"{purpose} without source configurations")
        case["current_candidate"]["source_configs"] = [copy.deepcopy(config)]
        case["current_candidate"]["source_configs"][0].pop("navigation")
        expect_rejected(analysis, case, f"{purpose} without existing navigation")
        if scope == "PROJECT_SOURCE":
            case = copy.deepcopy(repair)
            case["recovery_baselines"].append(copy.deepcopy(case["recovery_baselines"][0]))
            expect_rejected(analysis, case, f"{purpose} with multiple targets")

    case = copy.deepcopy(recheck)
    case["failure_context"] = None
    expect_rejected(analysis, case, "RECHECK_SOURCE without failure context")
    case = copy.deepcopy(recheck)
    case["sources"].append(copy.deepcopy(case["sources"][0]))
    expect_rejected(analysis, case, "RECHECK_SOURCE multiple sources")

    applied = SCHEMAS / "messages/dataset.applied.schema.json"
    case = read(ROOT / "examples/messages/dataset-end-result.json")
    case["committed_deletions"] = [{"project_source_id": recheck["sources"][0]["project_source_id"], "job_id": recheck["failure_context"]["source_job_id"]}]
    validate_instance(applied, case, "committed deletion with job_id")
    case["committed_deletions"][0].pop("job_id")
    expect_rejected(applied, case, "committed deletion without job_id")

    data_api = read(ROOT / "openapi/data.json")["paths"]["/api/projects/{p}/v{v}/records"]["get"]
    assert {"401", "403", "409", "410", "429", "503"} <= set(data_api["responses"])
    assert data_api["responses"]["429"]["headers"]["Retry-After"]["required"]
    assert data_api["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["meta"]["properties"]["has_more"]["type"] == "boolean"
    internal_api = read(ROOT / "openapi/internal.json")["paths"]["/internal/v1/project-sources/latest-records:query"]["post"]
    assert {"200", "503"} <= set(internal_api["responses"])
    assert internal_api["security"] == [{"InternalBearer": []}]
    latest_body = internal_api["responses"]["200"]["content"]["application/json"]["schema"]
    latest_example = source_examples(source, "16.1")[1]
    Draft202012Validator(latest_body, format_checker=FormatChecker()).validate(latest_example)
    too_many = copy.deepcopy(latest_example)
    too_many["projects"][0]["latest_records"] = [{"field_values": {}}] * 4
    assert list(Draft202012Validator(latest_body).iter_errors(too_many)), "최신값 4개가 허용됨"
    latest_error = internal_api["responses"]["503"]["content"]["application/json"]["schema"]
    Draft202012Validator(latest_error).validate(source_examples(source, "16.1")[2])
    control_api = read(ROOT / "openapi/control.json")["paths"]["/control/v1/projects/{p}/sources/{s}"]["delete"]
    assert {(p["name"], p["in"]) for p in control_api["parameters"]} >= {("analysis_id", "query"), ("expected_revision", "query")}
    auth_api = read(ROOT / "openapi/auth.json")
    assert auth_api["components"]["securitySchemes"]["RefreshCookie"]["name"] == "weavo_refresh"
    assert auth_api["paths"]["/auth/v1/refresh"]["post"]["security"] == [{"RefreshCookie": []}]
    for path in ("/auth/v1/email-verifications/confirm", "/auth/v1/password-reset-requests/confirm"):
        request = auth_api["paths"][path]["post"]["requestBody"]["content"]["application/json"]["schema"]
        validator = Draft202012Validator(request)
        assert validator.is_valid({"email": "user@example.com", "code": "012345"})
        assert not validator.is_valid({"email": "user@example.com", "code": "12345"})
        assert not validator.is_valid({"email": "user@example.com", "code": "12abcd"})
    internal = read(ROOT / "openapi/internal.json")["paths"]
    claim = internal["/internal/v1/jobs/{job_id}/attempts:claim"]["post"]
    assert {"200", "503"} <= set(claim["responses"])
    claim_schema = claim["responses"]["200"]["content"]["application/json"]["schema"]
    validator = Draft202012Validator(claim_schema, format_checker=FormatChecker())
    assert validator.is_valid({"status": "ACQUIRED", "lease_expires_at": "2026-11-06T00:02:00Z"})
    assert validator.is_valid({"status": "TERMINAL", "lease_expires_at": None})
    assert not validator.is_valid({"status": "BUSY", "lease_expires_at": None})
    heartbeat = internal["/internal/v1/jobs/{job_id}/attempts/{attempt_id}:heartbeat"]["post"]
    assert {"200", "409"} <= set(heartbeat["responses"])
    control = read(ROOT / "openapi/control.json")["paths"]
    assert "409" in control["/control/v1/projects"]["post"]["responses"]
    assert "409" in control["/control/v1/projects/{p}/analyses"]["post"]["responses"]
    assert "200" in control["/control/v1/jobs/{j}"]["get"]["responses"]
    project_request = control["/control/v1/projects"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    project = {"name": "project", "purpose": "test", "source_urls": ["https://example.com/list"], "email_enabled": False}
    assert Draft202012Validator(project_request).is_valid(project)
    assert not Draft202012Validator(project_request).is_valid({**project, "source_urls": []})
    patch_request = control["/control/v1/projects/{p}/analyses/{a}/candidate"]["patch"]["requestBody"]["content"]["application/json"]["schema"]
    connector = {"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "extract": {"type": "TEXT", "attribute": None}}
    candidate = {"expected_revision": 1, "source_rules": [{"project_source_id": "95f7d7de-ec00-4fbe-b430-fa3a79763c18", "field_connectors": [connector]}]}
    assert Draft202012Validator(patch_request).is_valid(candidate)
    connector["extract"] = {"type": "ATTRIBUTE", "attribute": ""}
    assert not Draft202012Validator(patch_request).is_valid(candidate)
    name_error = auth_api["paths"]["/auth/v1/me"]["patch"]["responses"]["422"]["content"]["application/json"]["schema"]
    assert name_error["properties"]["error"]["properties"]["code"]["const"] == "INVALID_NAME"
    for path, field in (("/auth/v1/signup", "password"), ("/auth/v1/password-resets", "new_password")):
        password_operation = auth_api["paths"][path]["post"]
        password = password_operation["requestBody"]["content"]["application/json"]["schema"]["properties"][field]
        assert password_operation["responses"]["422"]["content"]["application/json"]["schema"]["properties"]["error"]["properties"]["code"]["const"] == "INVALID_PASSWORD"
        validator = Draft202012Validator(password)
        assert validator.is_valid("Abcdef12!z")
        assert validator.is_valid("Abcdef12!z" * 7), "12자 또는 64자 상한을 임의로 적용함"
        assert not validator.is_valid("Abcdef1!z"), "10자 미만을 허용함"
        assert validator.is_valid("abcdefgh1!"), "영문 대문자를 필수로 요구함"
        assert not validator.is_valid("abcdefgh12"), "특수문자가 없는 비밀번호를 허용함"
        assert not validator.is_valid("abcdefgh!!"), "숫자가 없는 비밀번호를 허용함"
        assert not validator.is_valid("12345678!!"), "영문이 없는 비밀번호를 허용함"
    jobs = control["/control/v1/projects/{p}/jobs"]["get"]
    assert any(x["name"] == "project_source_id" and x["in"] == "query" for x in jobs["parameters"])
    jobs_schema = jobs["responses"]["200"]["content"]["application/json"]["schema"]
    job = {"job_id": "71c46a95-e328-47a5-92e7-a9018e5a5de6", "job_type": "COLLECTION", "status": "RUNNING", "created_at": "2026-11-16T00:00:00Z", "sources": []}
    assert Draft202012Validator(jobs_schema).is_valid({"jobs": [job] * 5})
    assert not Draft202012Validator(jobs_schema).is_valid({"jobs": [job] * 6})
    sample_schema = control["/control/v1/projects/{p}/analyses/{a}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]["properties"]["sources"]["items"]["properties"]["samples"]["items"]
    sample = {"sample_index": 0, "source_url": None, "fields": [{"field_id": "ba4f9e1e-ae8e-4a3c-9261-dca457848148", "raw_values": [], "match_count": None, "value": None, "status": "ABSENT", "error": None, "failure_expires_at": None}]}
    assert Draft202012Validator(sample_schema).is_valid(sample)
    sample["fields"][0]["status"] = "UNKNOWN"
    assert not Draft202012Validator(sample_schema).is_valid(sample), "정의되지 않은 샘플 상태 허용"
    assert {"202", "409", "422"} <= set(control["/control/v1/projects/{p}/analyses/{a}/approve"]["post"]["responses"])
    project["source_urls"] = [f"https://example.com/{i}" for i in range(30)]
    assert Draft202012Validator(project_request).is_valid(project), "출처 수에 제품 상한이 들어감"
    assert "requestBody" not in auth_api["paths"]["/auth/v1/me"]["delete"], "탈퇴 시 별도 비밀번호/확인값 요청"

    for p in (ROOT / "openapi").glob("*.json"):
        validate_openapi(read(p), base_uri=p.resolve().as_uri())
    operations = {(method.upper(), path) for p in (ROOT / "openapi").glob("*.json") for path, methods in read(p)["paths"].items() for method in methods}
    expected = {(item["method"], item["path"]) for item in inventory["http"]}
    if operations != expected:
        raise AssertionError(f"OpenAPI inventory mismatch: missing={expected - operations}; extra={operations - expected}")
    for p in (ROOT / "openapi").glob("*.json"):
        for path, methods in read(p)["paths"].items():
            for method, operation in methods.items():
                if not any(re.fullmatch(r"2\d\d", code) for code in operation["responses"]):
                    raise AssertionError(f"정확한 성공 코드 누락: {method.upper()} {path}")
                if "default" not in operation["responses"]:
                    raise AssertionError(f"공통 오류 누락: {method.upper()} {path}")
    for item in inventory["http"]:
        table_entry = f"| {item['method']} | `{item['path']}` |"
        if table_entry not in spec and item["path"] not in source:
            raise AssertionError(f"Endpoint missing from service contracts: {item['method']} {item['path']}")
    aliases = {"project_id": "p", "project_source_id": "s", "schema_version": "v", "record_id": "r"}

    def normalized(path):
        return re.sub(r"\{([^}]+)\}", lambda match: "{" + aliases.get(match.group(1), match.group(1)) + "}", path)

    normalized_operations = {(method, normalized(path)) for method, path in operations}
    for filename in ("service-message-contracts.md", "spec-decision-log.md", "service-message-flows.md"):
        pattern = r"\b(GET|POST|PATCH|DELETE|PUT)\s+(/(?:auth|control|api|internal)/[A-Za-z0-9_./:{}-]+)"
        for match in re.finditer(pattern, (DOCS / filename).read_text(encoding="utf-8")):
            method, path = match.groups()
            path = path.rstrip(".")
            if (method, normalized(path)) not in normalized_operations:
                raise AssertionError(f"Endpoint not covered from {filename}: {method} {path}")

    # 20개 기능 흐름과 상세 메시지 흐름의 제목·명시된 메시지 종류를 대조한다.
    flows = (DOCS / "service-message-flows.md").read_text(encoding="utf-8")
    def exact_section(text, number):
        match = re.search(r"^### " + re.escape(number) + r" (.+)$", text, re.M)
        if not match:
            raise AssertionError(f"Missing flow section: {number}")
        remaining = text[match.end():]
        end = re.search(r"^#{1,3} ", remaining, re.M)
        return match.group(1), remaining[:end.start()] if end else remaining
    for i in range(1, 21):
        name, left = exact_section(flows, f"3.{i}")
        source_name, right = exact_section(source, f"15.{i}")
        if name != source_name:
            raise AssertionError(f"Flow title mismatch {i}: {name} != {source_name}")
        left_types = {m["name"] for m in inventory["messages"] if m["name"] in left}
        right_types = {m["name"] for m in inventory["messages"] if m["name"] in right}
        if left_types != right_types:
            raise AssertionError(f"Flow message mismatch {i}: {left_types} != {right_types}")

    for service in ("analysis", "crawler", "control", "ingestion"):
        document = read(ROOT / "asyncapi" / f"{service}.json")
        declared = {item["name"] for item in document["components"]["messages"].values()}
        expected_messages = {item["name"] for item in inventory["messages"] if service in item["producer"].split("/") or service in item["consumer"].split("/")}
        if declared != expected_messages:
            raise AssertionError(f"{service} AsyncAPI message coverage: missing={expected_messages - declared}; extra={declared - expected_messages}")
        for op in document["operations"].values():
            channel = document["channels"][op["channel"]["$ref"].split("/")[-1]]
            for item in op["messages"]:
                message_name = item["$ref"].split("/")[-1]
                component = channel["messages"][message_name]["$ref"].split("/")[-1]
                event_name = document["components"]["messages"][component]["name"]
                row = next(row for row in inventory["messages"] if row["name"] == event_name)
                producers = row["producer"].split("/")
                if (op["action"] == "send") != (service in producers):
                    raise AssertionError(f"{service} direction incorrect: {event_name}")
    crawler = read(ROOT / "asyncapi/crawler.json")
    queues = {ch["address"] for ch in crawler["channels"].values() if "collection_execute" in ch["messages"]}
    if queues != {"collection-priority-jobs", "collection-scheduled-jobs"}:
        raise AssertionError(f"Crawler collection queues mismatch: {queues}")
    print(f"PASS: {schema_count} Draft7 schemas; {len(primary)+len(variants)} source examples; 13 full envelopes; negative cases; API contract checks; {len(operations)} HTTP operations; 20 flow pairs; 4 service message inventories")
    print("Scope: structural checks and source examples only; domain rules, service implementation and runtime interactions are not certified.")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ValueError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
