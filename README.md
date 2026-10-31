# ACH Payments

This repo will hold two systems:

1. **Same Day ACH Payments**: originates and processes Same Day ACH entries.
2. **ACH Fraud Monitoring & Prevention**: risk-based detection of fraudulent entries, as the 2026 Nacha rules require.

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

```sql
CREATE DATABASE ach_payments CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'ach_app'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON ach_payments.* TO 'ach_app'@'localhost';
FLUSH PRIVILEGES;
```

Connect as the app user:

```sh
mysql -u ach_app -p ach_payments
```

JDBC URL: `jdbc:mysql://localhost:3306/ach_payments`

> Keep credentials out of this repo. Store them locally, e.g. in `~/dev/mysql-credentials.txt` or a `.env` file. Both are gitignored.

## Status

- [x] Java 21, Tomcat 11, MySQL installed locally
- [ ] Requirements document
- [ ] Database schema
- [ ] Same Day ACH payments service
- [ ] Fraud monitoring service
