# Plan: API viewer pages and admin menu cleanup

Source: Ba's three documents (2026-11-07):
- *OpenAPI 3.0 Specification (banking-core-services.yaml)*: 5 operations
- *Open ACH Origination API* (`ach-origination-system.yaml`): 11 operations (the document says "10")
- *Open ACH Receiving API* (`ach-receiving-system.yaml`): 4 operations

Status: **plan approved in principle (decisions in section 5); nothing built yet.**

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
| V2 | **Ba's YAML is the source.** The YAML in the .docx files lost its line breaks, so I rebuild the three `.yaml` files with the same keys, values and order, and commit them unchanged otherwise. When Ba sends a new version, we replace the file and the page updates. |
| V3 | **Don't change Ba's specs**. The problems in section 4 are listed for Ba and handled in the viewer, not fixed in the YAML. |
| V4 | One reusable viewer component, used by both pages. It's needed twice, so it's not speculative. |
| V5 | The viewer also works in the GitHub Pages demo, because it's static. |
| V6 | No Swagger UI or Redoc. They are large, look different from the rest of the app, and don't give the dropdown that was asked for. |

## 3. Steps

| # | Step | Verify |
| --- | --- | --- |
| 1 | Rebuild the 3 YAML files under `frontend/src/apidocs/` | Each parses as YAML; operation counts are 5 / 11 / 4; a spot check of every summary and example against the .docx text |
| 2 | Add the `yaml` npm package (exact version). `ApiSpecPage` component: a spec picker (ACH page only), an operation dropdown (`METHOD path: summary`), and for the chosen operation its description, a parameters table (name, in, type, required, description, example), the request body fields, and a response section per status code with its fields (nested objects and arrays indented) | Vitest: list all operations; extract params, body and response fields for one operation from each spec, including OFAC's nested `hits[]` and RTN's path param |
| 3 | `App.tsx`: add the 2 admin routes and links; remove admin's Origination File, Receiving File and Receiving Transactions links and routes. `achorig` and `achrecv` are unchanged. | Browser: `admin` sees System Configuration, System Audit & App Logs, Open Core Banking API and ACH Payments API only; typing an old hash lands on System Configuration; `achorig` and `achrecv` menus are the same as before |
| 4 | `SecurityConfig`: remove `SYS_ADMIN` from `/api/v1/ach/receiving/**` and `/api/eod/**` (decision Q3) | Test: admin gets 403 on both; `achrecv` / `achorig` still get 200 |
| 5 | Docs: role tables in `README.md`, `docs/USE-CASES.md`, `docs/DEMO.md` | Read-through |
| 6 | Full check | All Java and frontend tests pass; browser check on the local app and the demo build |

Expected size: about 150–200 lines for the viewer plus the 3 YAML files. No database changes.

## 4. Problems in Ba's specs (for Ba; not changed)

| Spec | Problem | How the viewer handles it |
| --- | --- | --- |
| Origination, `GET /v1/holders/{holderId}/transfers/scheduled` | `required: - true` (a list), which isn't valid OpenAPI | Shown as required, since path parameters always are |
| Origination | Intro says "all 10 requested methods", but 11 operations are defined | Shows all 11 |
| Core, RTN validate | Description says "8 or 9-digit", the parameter says "9-digit" | Shown as written |
| Origination, outbound transfer | `recurrenceDetails` is `nullable` but lists required fields (it means "required when present") | Shown as written |
| Receiving search | `processingStatus` could mean our `processStatus` or our `fedStatus` column (the response returns `fedStatus`) | Shown as written. Matters only when the API is built. |

## 5. Decisions (2026-11-07)

| # | Question | Decision |
| --- | --- | --- |
| Q1 | One ACH link or two? | **One link, "ACH Payments API", with an Origination / Receiving picker.** That matches "add 2 new links" (Core Banking + ACH Payments) while following Ba's split into two specs. |
| Q2 | Documentation only, or "Try it"? | **Documentation only.** |
| Q3 | Should admin keep server access to the ACH screens' APIs? | **No, remove it** with the screens. UC-ACH-RCV-02 never granted it to admin. |
| Q4 | How to read the YAML? | **The `yaml` npm package.** Ba's files go in as-is. |
