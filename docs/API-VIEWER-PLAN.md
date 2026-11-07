# Plan: API viewer pages and admin menu cleanup

Source: Ba's three documents (2026-11-07):
- *OpenAPI 3.0 Specification (banking-core-services.yaml)*: 5 operations
- *Open ACH Origination API* (`ach-origination-system.yaml`): 11 operations (the document says "10")
- *Open ACH Receiving API* (`ach-receiving-system.yaml`): 4 operations

Status: **built 2026-11-07** (decisions in section 5). The YAML files are in `frontend/src/apidocs/`.

---

## 1. What was asked

1. In the `admin` UI, remove **ACH Origination File**, **ACH Receiving File** and **ACH Receiving Transactions**. They already exist in the `achorig` and `achrecv` logins.
2. **ACH Receiving Transactions** stays in `achrecv` (it's already there), so this is just the removal from `admin`.
3. In the `admin` UI, add two links:
   - **Open Core Banking API**: browse the 5 core operations from a dropdown and see each one's name, description, input parameters and output.
   - **ACH Payments API**: the same viewer for Ba's two ACH specs (Origination and Receiving).

## 2. Assumptions

| # | Assumption |
| --- | --- |
| V1 | The viewer is **documentation only**. It shows what each API takes and returns but doesn't call anything. The `/v1/...` endpoints in the specs mostly don't exist in our server yet (we have `/api/routing/{routingNo}`, the banking calendar and OFAC inside transfers). |
| V2 | **Ba's YAML is the source.** The YAML in the .docx files lost its line breaks, so we rebuild the three `.yaml` files ourselves with the same keys, values and order. When Ba sends a new version, we replace the file and the page updates. |
| V3 | **Keep Ba's specs as written**, except the two clear typos fixed in section 4. Everything else in section 4 is resolved by a decision, not by editing the YAML. |
| V4 | One reusable viewer component, used by both pages. It's needed twice, so it's not speculative. |
| V5 | The viewer also works in the GitHub Pages demo, because it's static. |
| V6 | No Swagger UI or Redoc. They are large, look different from the rest of the app, and don't give the dropdown that was asked for. |

## 3. Steps

| # | Step | Verify |
| --- | --- | --- |
| 1 | Rebuild the 3 YAML files under `frontend/src/apidocs/`, with the 2 typo fixes from section 4 | Each parses as YAML; operation counts are 5 / 11 / 4; a script compares every scalar (summary, description, example, enum value) with the .docx text, and the only differences are the 2 fixes |
| 2 | Add the `yaml` npm package (exact version). `ApiSpecPage` component: a spec picker (ACH page only), an operation dropdown listing each operation's name (its `summary`, as in the request), and for the chosen operation its method and path, description, a parameters table (name, in, type, required, description, example), the request body fields, and a response section per status code with its fields (nested objects and arrays indented) | Vitest: list all operations; extract params, body and response fields for one operation from each spec, including OFAC's nested `hits[]` and RTN's path param |
| 3 | `App.tsx`: add the 2 admin routes and links; remove admin's Origination File, Receiving File and Receiving Transactions links and routes. `achorig` and `achrecv` are unchanged. | Browser: `admin` sees System Configuration, System Audit & App Logs, Open Core Banking API and ACH Payments API only; typing an old hash lands on System Configuration; `achorig` and `achrecv` menus are the same as before |
| 4 | `SecurityConfig`: remove `SYS_ADMIN` from `/api/v1/ach/receiving/**` and `/api/eod/**` (decision Q3) | Test: admin gets 403 on both; `achrecv` / `achorig` still get 200 |
| 5 | Docs: role tables in `README.md`, `docs/USE-CASES.md`, `docs/DEMO.md` | Read-through |
| 6 | Full check | All Java and frontend tests pass; browser check on the local app and the demo build |

Expected size: about 150–200 lines for the viewer plus the 3 YAML files. No database changes.

## 4. Problems in Ba's specs (resolved 2026-11-07)

| # | Spec | Problem | Resolution |
| --- | --- | --- | --- |
| P1 | Origination, `GET /v1/holders/{holderId}/transfers/scheduled` | `holderId` has `required: - true` (a list), which isn't valid OpenAPI | **Fixed in the YAML to `required: true`.** OpenAPI requires path parameters to be `required: true`, and every other `holderId` in the spec already is. |
| P2 | Core, `GET /v1/routing-numbers/{routingNumber}/validate` | Description says "8 or 9-digit", but the parameter says "9-digit" | **Fixed in the YAML to "9-digit".** Ba's mod-10 formula uses digits d1–d9, and our `RoutingService` already accepts only 9 digits. |
| P3 | Origination | Intro says "all 10 requested methods", but 11 operations are defined | **All 11 are kept.** Each one is a distinct use (add / list external accounts, 2 eligibility checks, eligible accounts, 2 transfer types, update, cancel, history, scheduled), so "10" is a miscount. The intro sentence isn't part of the YAML, so nothing changes. |
| P4 | Origination, outbound transfer | `recurrenceDetails` is `nullable` but lists required fields | **Not a bug.** It means `frequency` and `totalOccurrences` are required when `recurrenceDetails` is sent (for `RECURRING`). Shown as written. |
| P5 | Receiving, `GET /v1/ach/receiving/transactions/search` | `processingStatus` could mean our `processStatus` or our `fedStatus` column | **It filters on `fedStatus`.** The response returns `fedStatus`, and Ba's example `SETTLED` is a `fedStatus` value. Shown as written. Matters only when the API is built. |

## 5. Decisions (2026-11-07)

| # | Question | Decision |
| --- | --- | --- |
| Q1 | One ACH link or two? | **One link, "ACH Payments API", with an Origination / Receiving picker.** That matches "add 2 new links" (Core Banking + ACH Payments) while following Ba's split into two specs. |
| Q2 | Documentation only, or "Try it"? | **Documentation only.** |
| Q3 | Should admin keep server access to the ACH screens' APIs? | **No, remove it** with the screens. UC-ACH-RCV-02 never granted it to admin. |
| Q4 | How to read the YAML? | **The `yaml` npm package.** Ba's files go in as-is. |
