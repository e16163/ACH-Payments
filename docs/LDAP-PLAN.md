# LDAP login plan (Ba's 2026-11-13 exercise)

Goal: users, passwords, roles and permissions live in an LDAP server, and the ACH Payments app signs people in against it.
**Status: built 2026-11-13.** Before this, the five logins were hard-coded in `SecurityConfig.users()` from environment variables. They are now the fallback.

## 1. Ba's roles and permissions (kept exactly)

| Role | Permissions |
|---|---|
| MEMBER | ACH_MEMBER_UI |
| ACH_ORIG | ACH_ORIG_Reversal_UI, ACH_ORIG_Return_UI, ACH_ORIG_Resubmision_UI, ACH_ORIG_PROCESS |
| ACH_RECV | ACH_RECV_Reversal_UI, ACH_RECV_Return_UI, ACH_RECV_Resubmision_UI, ACH_RECV_PROCESS |
| ADMIN | ACH_BUSINESS_RULES, System logs, system configuration, open banking api, ACH payments api |

Ba gave the last four ADMIN permissions no code names. They are `SYSTEM_LOGS`, `SYSTEM_CONFIG`, `OPEN_BANKING_API` and `ACH_PAYMENTS_API`.
Ba's spelling `Resubmision` is kept in the permission names.

## 2. LDAP server

OpenLDAP (`slapd`) from Homebrew, the same way MySQL runs. It listens on `ldap://localhost:1389`, since macOS keeps port 389 for root. The base DN is `dc=achpayments,dc=local`.

```
dc=achpayments,dc=local
├── ou=people        uid=<userid>   inetOrgPerson, userPassword (SSHA hash)
├── ou=roles         cn=MEMBER      groupOfNames, member: uid=...,ou=people,...
│                    cn=ACH_ORIG / cn=ACH_RECV / cn=ADMIN
└── ou=permissions   cn=ACH_MEMBER_UI   groupOfNames, member: cn=MEMBER,ou=roles,...
                     cn=ACH_ORIG_Return_UI ... (one entry per permission)
```

A user gets roles by being a `member` of a role group. A role gets permissions by being a `member` of a permission group.
That matches Ba's model: create user, assign roles, define permissions, assign permissions to a role.

The repo holds `ldap/slapd.conf` and `ldap/seed.ldif` with the tree, the four roles, all permissions and one demo user per role.
Admins add users with `ldapadd`/`ldapmodify`, or with a GUI such as Apache Directory Studio. No user-admin screen in the app.

## 3. App changes

1. Add `spring-security-ldap`. Sign-in binds to LDAP as the user, so the password check happens on the LDAP server.
2. After sign-in, look up the user's role groups, then those roles' permission groups. Grant each as an authority (`ROLE_ACH_ORIG`, `ACH_ORIG_Return_UI`, ...).
3. `SecurityConfig` checks **permissions**, not roles, on each URL:

| Permission | URLs / screens |
|---|---|
| ACH_MEMBER_UI | `/api/member/**`, `/api/transfers/**`, `/api/routing/**`, `/api/v1/holders/**` |
| ACH_ORIG_PROCESS | `/api/v1/ach/origination/**`, `/api/v1/ach/processing/**`, `/api/v1/ach/limits/**`, `/api/v1/ach/transfers/**`, `/api/v1/accounts/**`, `/api/eod/**` |
| ACH_ORIG_Return_UI / Reversal_UI / Resubmision_UI | `/api/v1/ach/back-office/origination/{return,reversal,resubmission}` and those screens |
| ACH_RECV_PROCESS | `/api/v1/ach/receiving/**` |
| ACH_RECV_Return_UI / Reversal_UI | `/api/v1/ach/back-office/receiving/{return,reversal}` and those screens |
| ACH_BUSINESS_RULES | `/api/ach-business-rules/**` |
| SYSTEM_LOGS | `/api/v1/admin/logs/**` |
| SYSTEM_CONFIG | `/api/params/**` |
| OPEN_BANKING_API / ACH_PAYMENTS_API | the Core API and ACH API viewer pages |

4. `/api/me` returns permissions too. `App.tsx` and `backoffice/screens.ts` show menus by permission instead of role.
5. The member's number (now `app.member-number`) moves to the user's LDAP entry, in the `employeeNumber` attribute.
6. `FailedSignInLog` checks "does this user exist" against LDAP instead of the in-memory list.
7. Member MFA stays as it is.
8. Tests run against an embedded LDAP server (UnboundID, test scope only) loaded from the same `seed.ldif`.

## 4. Done when

- `slapd` runs locally and `ldapsearch` shows the seeded tree.
- Each seeded user signs in to the deployed app and sees only their permitted screens. Other URLs return 403.
- A wrong password is refused and logged as today.
- A new user added with `ldapadd` and put in a role can sign in without restarting the app.
- `mvn test` passes.

## 5. Open questions

Answered 2026-11-13:

- **Server.** OpenLDAP via Homebrew.
- **Extra roles.** COMPLIANCE stays as a 5th role with permission `OFAC_REVIEW_UI` (`/api/v1/compliance/**`). SUPPORT is dropped, since it has no login today.
- **Screens.** `ACH_RECV_Resubmision_UI` is created in LDAP as Ba wrote it, with no screen behind it. The ORIG and RECV NOC screens fall under `ACH_ORIG_PROCESS` and `ACH_RECV_PROCESS`.
- **Old logins.** The five env-var logins stay as a fallback. Spring tries LDAP first, then the in-memory users. They get the same permissions as the matching LDAP role, so the URL rules see one model. `FailedSignInLog` checks both sources for "does this user exist".

## 6. Notes from the build

- Anyone can read the directory except passwords (`access to * by * read`). The app looks up roles and permissions without signing in to LDAP. A real bank would give the app its own read account instead.
- Sign-in tries the fallback logins first, then LDAP. With LDAP first, a connection error would end the sign-in and lock everyone out while LDAP is down.
- The fallback logins can't be locked or disabled from LDAP. Remove them (`SecurityConfig.users()`) once LDAP is the only source of users.
- Spring Security 7 adds a `FACTOR_PASSWORD` authority on every sign-in. `/api/me` leaves it out of `permissions`.
- `LdapLoginTest` runs an in-memory LDAP server loaded from the same `seed.ldif`. It checks that the seeded roles get exactly the permissions in `SecurityConfig.PERMISSIONS`, so the seed and the fallback map can't drift apart.
