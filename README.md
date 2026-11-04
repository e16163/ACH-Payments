# ACH Payments

This repo will hold two systems:

1. **Same Day ACH Payments**: originates and processes Same Day ACH entries.
2. **ACH Fraud Monitoring & Prevention**: risk-based detection of fraudulent entries, as the 2026 Nacha rules require.

**Try the demo:** https://e16163.github.io/ACH-Payments/ (sign in as `member` / `member123` or `admin` / `admin123`). It's the real screens running on made-up data in your browser. GitHub Pages can't run the Java server or MySQL, so nothing is saved, and the demo resets when you close the tab. It's rebuilt from `main` on every push (`.github/workflows/pages.yml`, `npm run build:demo`).

## Background

- **Rules:** set by Nacha. Payments are processed by the Federal Reserve (FedACH) and EPN, and 31 CFR Part 210 governs.
- **Same Day ACH limit:** $1M per payment, rising to **$10M on Sept 17, 2027**. Keep this configurable.
- **Entry descriptions:** payroll entries must use `PAYROLL` and e-commerce entries must use `PURCHASE`.
- **WEB debits:** require a "commercially reasonable fraudulent transaction detection system".

## Local setup (macOS, Apple Silicon)

### 1. Java 21

Install a JDK 21 (Oracle or Temurin), then check it:

```sh
java -version
```

### 2. Homebrew

```sh
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
```

Open a new terminal afterwards.

### 3. Apache Tomcat 11

```sh
mkdir -p ~/dev && cd ~/dev
curl -LO https://dlcdn.apache.org/tomcat/tomcat-11/v11.0.27/bin/apache-tomcat-11.0.27.tar.gz
tar xzf apache-tomcat-11.0.27.tar.gz
ln -s apache-tomcat-11.0.27 tomcat
```

| Action | Command |
| --- | --- |
| Start | `~/dev/tomcat/bin/startup.sh` |
| Stop | `~/dev/tomcat/bin/shutdown.sh` |
| Check | open http://localhost:8080 |

If the version above 404s, pick the latest from https://dlcdn.apache.org/tomcat/tomcat-11/.

### 4. MySQL

```sh
brew install mysql
brew services start mysql      # runs in the background, restarts at login
mysql_secure_installation      # set a root password
```

Create the project database and app user. Pick your own password:

```sh
mysql -u root -p
```

Following Ba's diagram, there are separate databases: `ach_payments` (shared config and logging), `ach_receiving` and `ach_origination`. Flyway creates the tables in all three.

```sql
CREATE DATABASE ach_payments CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE ach_receiving CHARACTER SET utf8mb4;
CREATE DATABASE ach_origination CHARACTER SET utf8mb4;
CREATE USER 'ach_app'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON ach_payments.* TO 'ach_app'@'localhost';
GRANT ALL PRIVILEGES ON ach_receiving.* TO 'ach_app'@'localhost';
GRANT ALL PRIVILEGES ON ach_origination.* TO 'ach_app'@'localhost';
FLUSH PRIVILEGES;
```

Connect as the app user:

```sh
mysql -u ach_app -p ach_payments
```

JDBC URL: `jdbc:mysql://localhost:3306/ach_payments`

> Keep credentials out of this repo. Store them locally, e.g. in `~/dev/mysql-credentials.txt` or a `.env` file. Both are gitignored.

### 5. Build and run

The app reads its secrets from the environment:
- `JDBC_URL`, `DB_USER` and `DB_PASSWORD` go in `~/dev/mysql-credentials.txt`.
- `APP_ADMIN_PASSWORD`, `APP_OPERATOR_PASSWORD` and `APP_MEMBER_PASSWORD` go in `~/dev/app-credentials.txt` (optionally `APP_ADMIN_USERNAME`, `APP_OPERATOR_USERNAME` and `APP_MEMBER_USERNAME`). These are the demo logins (see below).

Write them as `KEY=value` lines, then create `~/dev/tomcat/bin/setenv.sh` so Tomcat loads them:

```sh
#!/bin/sh
set -a
. "$HOME/dev/mysql-credentials.txt"
. "$HOME/dev/app-credentials.txt"
set +a
```

Build the app and deploy it. `mvn package` also downloads Node and builds the React app, so you don't need Node installed.

```sh
mvn package                                  # runs tests, builds target/ach.war
cp target/ach.war ~/dev/tomcat/webapps/
~/dev/tomcat/bin/shutdown.sh; ~/dev/tomcat/bin/startup.sh
```

Then check it:
- http://localhost:8080/ach/ should show the Sign in page, with **Database: OK** at the bottom.
- http://localhost:8080/ach/api/health should return `{"db":"OK"}`.

For frontend work with live reload, keep Tomcat running and run:

```sh
cd frontend && ./node/npm run dev
```

Then open http://localhost:5173/ach/.

### 6. Demo logins

The app opens on a **Sign in** page, and **Sign out** is at the top right. These are demo users only; real logins come in roadmap step 16. A local demo can use simple passwords, e.g. `member123` and `operator123`.

| User | Role | Can use |
| --- | --- | --- |
| `admin` (or `APP_ADMIN_USERNAME`) | System Administrator | Parameter UI |
| `operator` | Operator | Not the Parameter UI (gets "403 Forbidden") |
| `member` | Member: Ba's sample member M10982347, John Doe (`APP_MEMBER_NUMBER`) | Online banking: Transfer & Pay. After the password, enter the 6-digit code shown on screen (simulated MFA). |

Scripts and tests can still send HTTP Basic credentials, but a browser never gets the Basic login popup.

### 7. Running the database tests

Some tests run against real MySQL, in separate **test** databases so they never touch your data. `ba_reference_test` holds Ba's original DDL, which a test compares our schema against. One-time setup, as root:

```sql
CREATE DATABASE ach_payments_test CHARACTER SET utf8mb4;
CREATE DATABASE ach_receiving_test CHARACTER SET utf8mb4;
CREATE DATABASE ach_origination_test CHARACTER SET utf8mb4;
CREATE DATABASE ba_reference_test CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON ach_payments_test.* TO 'ach_app'@'localhost';
GRANT ALL PRIVILEGES ON ach_receiving_test.* TO 'ach_app'@'localhost';
GRANT ALL PRIVILEGES ON ach_origination_test.* TO 'ach_app'@'localhost';
GRANT ALL PRIVILEGES ON ba_reference_test.* TO 'ach_app'@'localhost';
```

Then run the tests with `TEST_JDBC_URL` set. Without it, the database tests are skipped and everything else still runs.

```sh
set -a; . ~/dev/mysql-credentials.txt; set +a
export TEST_JDBC_URL=jdbc:mysql://localhost:3306/ach_payments_test
mvn test
```

## Status

- [x] Java 21, Tomcat 11, MySQL installed locally
- [x] Requirements and implementation plan ([docs/PLAN.md](docs/PLAN.md))
- [x] Step 0: Spring Boot + React skeleton deployed on Tomcat, with DB health check
- [x] Step 1: NACHA file parser
- [x] Parameter UI (UC-SYS-PARAM-01): designers add/edit parameters per app
- [x] Ba's design doc and DB model reviewed ([docs/DB-MODEL.md](docs/DB-MODEL.md), source in [docs/design/](docs/design/))
- [x] Database schema: all 30 of Ba's tables exactly as written, plus approved additions (V1–V10), with Ba's sample data
- [x] ACH Origination Transfer UI (UC-ACH-ORG-01): member login with MFA, external transfer, limits, OFAC, memo hold
- [ ] Same Day ACH payments service
- [ ] Fraud monitoring service
