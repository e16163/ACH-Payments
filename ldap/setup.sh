#!/bin/sh
# Starts the local OpenLDAP server (docs/LDAP-PLAN.md) on ldap://localhost:1389.
# The first run creates ~/dev/ldap, loads seed.ldif and sets the demo users' passwords. Later runs only start slapd.
# To rebuild from seed.ldif, stop slapd and delete ~/dev/ldap/data (users added since are lost).
set -eu
BREW=/opt/homebrew/opt/openldap
SCHEMA=/opt/homebrew/etc/openldap/schema
DIR="$HOME/dev/ldap"
CREDS="$HOME/dev/ldap-credentials.txt"
BASE="dc=achpayments,dc=local"
URL="ldap://localhost:1389"

if [ ! -f "$CREDS" ]; then
    (umask 077; printf 'LDAP_ADMIN_PASSWORD=%s\nLDAP_USER_PASSWORD=%s\n' "$(openssl rand -hex 12)" "$(openssl rand -hex 6)" > "$CREDS")
fi
. "$CREDS"

if [ -f "$DIR/slapd.pid" ] && kill -0 "$(cat "$DIR/slapd.pid")" 2>/dev/null; then
    echo "slapd is already running"
    exit 0
fi

first_run=false
if [ ! -d "$DIR/data" ]; then
    first_run=true
    mkdir -p "$DIR/data"
    cat > "$DIR/slapd.conf" <<EOF
include     $SCHEMA/core.schema
include     $SCHEMA/cosine.schema
include     $SCHEMA/inetorgperson.schema
pidfile     $DIR/slapd.pid
argsfile    $DIR/slapd.args
modulepath  $BREW/libexec/openldap
moduleload  back_mdb.la

# Passwords are only usable to sign in; the app reads users, roles and permissions anonymously.
access to attrs=userPassword by self write by anonymous auth by * none
access to * by * read

database    mdb
maxsize     1073741824
suffix      "$BASE"
rootdn      "cn=admin,$BASE"
rootpw      $("$BREW/sbin/slappasswd" -s "$LDAP_ADMIN_PASSWORD")
directory   $DIR/data
index       objectClass,uid,member eq
EOF
    "$BREW/sbin/slapadd" -f "$DIR/slapd.conf" -l "$(dirname "$0")/seed.ldif"
fi

"$BREW/libexec/slapd" -f "$DIR/slapd.conf" -h "$URL/"
echo "slapd started on $URL"

if $first_run; then
    for user in ldapadmin ldaporig ldaprecv ldapcomp ldapmember; do
        "$BREW/bin/ldappasswd" -x -H "$URL" -D "cn=admin,$BASE" -w "$LDAP_ADMIN_PASSWORD" -s "$LDAP_USER_PASSWORD" "uid=$user,ou=people,$BASE"
    done
    echo "Demo users' password is LDAP_USER_PASSWORD in $CREDS"
fi
