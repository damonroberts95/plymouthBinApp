#!/usr/bin/env python3
"""Drift detector: verifies the Plymouth council AchieveForms lookups still behave
the way the Android app expects. Run weekly via GitHub Actions.

Fails (non-zero exit) if:
  - Bootstrap can't acquire sid/csrf within timeout
  - LOOKUP_ADDRESS_SEARCH doesn't return rows for a known good postcode
  - LOOKUP_COLLECTIVE_KEY doesn't return a key for the picked UPRN
  - LOOKUP_SCHEDULE_PRIMARY doesn't return rows for the address
  - Schema fields drift (collectiveCollectionDate / collectiveWasteType / collectiveWorkpackName
    missing from row data)
  - Required round prefix (H##/G##) format changed

On failure, the workflow opens (or updates) a tracking issue.
"""
import json
import re
import sys
import time
from datetime import datetime, timedelta

import requests

BASE = "https://plymouth-self.achieveservice.com"
FORM_URL = (
    f"{BASE}/AchieveForms/?mode=fill&consentMessage=yes"
    "&form_uri=sandbox-publish://AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
    "/AF-Stage-67ba684d-0a5b-48f8-9c50-1c01cc43c396/definition.json"
    "&process=1"
    "&process_uri=sandbox-processes://AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
    "&process_id=AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
)
FILLFORM_URL = f"{BASE}/fillform/?iframe_id=fillform-frame-1&db_id="
STAGE_ID = "AF-Stage-67ba684d-0a5b-48f8-9c50-1c01cc43c396"
STAGE_NAME = "Check bin day"
PROCESS_ID = "AF-Process-084d6742-3572-41ba-ac1a-430750451f9d"
FORM_ID = "AF-Form-2782d5d8-9470-4a9c-b5f1-5f4e83904d7f"

LOOKUP_ADDRESS_SEARCH = "560d5266e930f"
LOOKUP_COLLECTIVE_KEY = "6936e38f6d376"
LOOKUP_PREMISE = "69f05bb2ad2d6"
LOOKUP_SCHEDULE_PRIMARY = "698b9c49a3c13"

USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")

# Known good test case: a stable residential address.
TEST_POSTCODE = "PL54LX"
TEST_ADDRESS_REGEX = r"^138 Lake View Close"

REQUIRED_ROW_FIELDS = {
    "collectiveCollectionDate",
    "collectiveWasteType",
    "collectiveWorkpackName",
}
ROUND_RE = re.compile(r"^Waste-([A-Z]\d+)-\d{6}$")

errors: list[str] = []


def fail(msg: str) -> None:
    print(f"::error::DRIFT: {msg}", flush=True)
    errors.append(msg)


def field(name: str, value: str, ftype: str = "text") -> dict:
    return {"name": name, "type": ftype, "value": value, "value_changed": True,
            "hidden": False, "_hidden": True, "valid": True,
            "isMandatory": False, "isRepeatable": False}


def bootstrap():
    from playwright.sync_api import sync_playwright
    cap = {"sid": None, "csrf": None}

    def on_request(req):
        if "/apibroker/runLookup" not in req.url:
            return
        if not cap["sid"]:
            m = re.search(r"[?&]sid=([^&]+)", req.url)
            if m: cap["sid"] = m.group(1)
        if not cap["csrf"]:
            m = re.search(r'"csrf_token"\s*:\s*"([a-f0-9]+)"', req.post_data or "")
            if m: cap["csrf"] = m.group(1)

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(user_agent=USER_AGENT)
        page = ctx.new_page()
        page.on("request", on_request)
        page.goto(FORM_URL, timeout=60000)
        page.wait_for_selector("iframe#fillform-frame-1", timeout=30000)
        deadline = time.monotonic() + 30
        while not (cap["sid"] and cap["csrf"]):
            if time.monotonic() > deadline: break
            page.wait_for_timeout(300)
        cookies = ctx.cookies()
        browser.close()
    if not (cap["sid"] and cap["csrf"]):
        return None, None, None
    return cap["sid"], cap["csrf"], cookies


def post_lookup(session, sid, csrf, lookup_id, form_values, label):
    params = {"id": lookup_id, "repeat_against": "", "noRetry": "false",
              "getOnlyTokens": "undefined", "log_id": "",
              "app_name": "AF-Renderer::Self",
              "_": str(int(time.time() * 1000)), "sid": sid}
    body = {"stopOnFailure": True, "usePHPIntegrations": True,
            "stage_id": STAGE_ID, "stage_name": STAGE_NAME,
            "formId": FORM_ID, "processId": PROCESS_ID,
            "formValues": {"Section 1": form_values},
            "tokens": {"csrf_token": csrf}}
    headers = {"x-requested-with": "XMLHttpRequest",
               "content-type": "application/json",
               "referer": FILLFORM_URL, "accept": "*/*",
               "User-Agent": USER_AGENT}
    print(f"[drift] POST {label} ({lookup_id})...", flush=True)
    r = session.post(f"{BASE}/apibroker/runLookup",
                     params=params, json=body, headers=headers, timeout=45)
    if not r.ok:
        fail(f"{label}: HTTP {r.status_code}")
        return None
    return r.json()


def get_rows(data):
    if not data: return []
    rows = (data.get("integration") or {}).get("transformed", {}).get("rows_data") or {}
    if isinstance(rows, dict): rows = list(rows.values())
    return rows


def main():
    print(f"[drift] starting drift detection {datetime.now().isoformat()}", flush=True)

    sid, csrf, cookies = bootstrap()
    if not sid:
        fail("bootstrap could not acquire sid/csrf within 30s")
        return 1

    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "en-GB,en;q=0.9"})
    for c in cookies:
        session.cookies.set(c["name"], c["value"], domain=c.get("domain"))

    # 1. Address search
    fv = {
        "postcode_search": field("postcode_search", TEST_POSTCODE),
        "chooseAddress": field("chooseAddress", "", "select"),
    }
    d = post_lookup(session, sid, csrf, LOOKUP_ADDRESS_SEARCH, fv, "address_search")
    addrs = get_rows(d)
    if not addrs:
        fail("LOOKUP_ADDRESS_SEARCH returned no rows for known-good postcode")
        return 1
    sample = addrs[0]
    if "uprn" not in sample or "display" not in sample:
        fail(f"LOOKUP_ADDRESS_SEARCH row schema changed: keys={list(sample.keys())[:10]}")

    rx = re.compile(TEST_ADDRESS_REGEX, re.I)
    match = next((a for a in addrs if rx.search(a.get("display", ""))), None)
    if not match:
        fail(f"test address /{TEST_ADDRESS_REGEX}/ no longer in postcode {TEST_POSTCODE}")
        return 1
    uprn = match["uprn"]
    print(f"[drift] picked {match['display']} uprn={uprn}", flush=True)

    # 2. Key lookup
    fv = {
        "collectiveUPRN": field("collectiveUPRN", uprn),
        "collectiveUPRNGarden": field("collectiveUPRNGarden", uprn),
        "UPRN": field("UPRN", uprn),
        "collectivePremiseDetailGetUPRN": field("collectivePremiseDetailGetUPRN", uprn),
    }
    d = post_lookup(session, sid, csrf, LOOKUP_COLLECTIVE_KEY, fv, "collective_key")
    rows = get_rows(d)
    if not rows or "collectiveKey" not in (rows[0] or {}):
        fail("LOOKUP_COLLECTIVE_KEY no longer returns collectiveKey field")
        return 1
    key = rows[0]["collectiveKey"]
    if len(key) < 40:
        fail(f"LOOKUP_COLLECTIVE_KEY returned suspiciously short key ({len(key)} chars)")

    # 3. Schedule lookup
    now = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
    start = (now - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S")
    end = (now + timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%S")
    fv = {
        "collectiveKey": field("collectiveKey", key, "textarea"),
        "collectiveUPRNGarden": field("collectiveUPRNGarden", uprn),
        "collectiveUPRN": field("collectiveUPRN", uprn),
        "UPRN": field("UPRN", uprn),
        "collectivePremiseDetailGetUPRN": field("collectivePremiseDetailGetUPRN", uprn),
        "collectiveGetJobStartDate": field("collectiveGetJobStartDate", start),
        "collectiveGetJobEndDate": field("collectiveGetJobEndDate", end),
    }
    d = post_lookup(session, sid, csrf, LOOKUP_SCHEDULE_PRIMARY, fv, "schedule_primary")
    rows = get_rows(d)
    rows = [r for r in rows if r.get("collectiveWasteType") != "No jobs found"]
    if not rows:
        fail("LOOKUP_SCHEDULE_PRIMARY returned no schedule rows for test address")
        return 1

    sample = rows[0]
    missing = REQUIRED_ROW_FIELDS - set(sample.keys())
    if missing:
        fail(f"Schedule row schema drift: missing fields {missing}")
    wp = sample.get("collectiveWorkpackName", "")
    if not ROUND_RE.match(wp or ""):
        fail(f"Round format drift: '{wp}' no longer matches Waste-[A-Z]\\d+-\\d{{6}}")

    print(f"[drift] OK: bootstrap + 3 lookups + schema all valid ({len(rows)} schedule rows)", flush=True)
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
