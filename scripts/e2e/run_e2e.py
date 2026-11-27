#!/usr/bin/env python3
"""
Milestone 11 end-to-end run: scenarios S1-S9 plus failure paths and the Q3 client-role check, against the
REAL local stack (Keycloak, Postgres, cce-admin-service, gateway-service, cce-protocol-service,
cce-insights-service). No mocks.

What it touches (and removes again in `finally`):
  * Keycloak (realm cce): temporary users/roles named e2e-* / E2E_*; cce-admin-ui's "direct access grants"
    switch is turned ON for the run and put back to its original value afterwards.
  * ccedb: rows for those users/roles, their api_permissions rows and the audit rows they caused.
  * two throw-away gateway instances (ports 8190 default claim path, 8191 client-role claim path). The
    developer's own gateway on 8090 is only touched by scenario S6 (stopped, then restarted with the same
    environment) unless --skip-s6 is given.

Usage:  python3 -I scripts/e2e/run_e2e.py [--skip-s6] [--json out.json]
Needs: docker (container cce-local-postgres), the stack already running, gateway jar built.
"""
import argparse, json, os, signal, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

KC = os.environ.get("KC", "http://localhost:8081")
ADMIN = os.environ.get("ADMIN", "http://localhost:8085")
GW_MAIN = os.environ.get("GW_MAIN", "http://localhost:8090")
GW_A = "http://localhost:8190"  # default claim path realm_access.roles (what local uses)
GW_B = "http://localhost:8191"  # deployed claim path resource_access.gateway-service.roles
HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.environ.get("GATEWAY_JAR", os.path.join(HERE, "..", "..", "..", "gateway-service", "build", "libs", "gateway-service-0.0.1-SNAPSHOT.jar"))
RUN = uuid.uuid4().hex[:6]
RESULTS = []


# ---------------------------------------------------------------- helpers
def http(method, url, body=None, headers=None, form=None, timeout=30):
    data, h = None, dict(headers or {})
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw and raw[0] in "{[" else raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw
    except Exception as e:  # connection refused, timeout ...
        return 0, str(e)


def psql(sql):
    out = subprocess.run(["docker", "exec", "-e", "PGPASSWORD=cce_pass", "cce-local-postgres", "psql", "-U", "cce_user", "-d", "ccedb", "-At", "-F", "|", "-c", sql],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.strip())
    return [r for r in out.stdout.strip().split("\n") if r]


def check(sid, name, ok, detail=""):
    RESULTS.append({"scenario": sid, "check": name, "ok": bool(ok), "detail": str(detail)[:300]})
    print(("  PASS " if ok else "  FAIL ") + f"[{sid}] {name}" + (f"  -> {detail}" if detail and not ok else ""))
    return ok


def note(sid, name, detail):
    RESULTS.append({"scenario": sid, "check": name, "ok": None, "detail": str(detail)[:400]})
    print(f"  INFO [{sid}] {name}: {detail}")


# ---------------------------------------------------------------- keycloak
class Keycloak:
    def __init__(self):
        self.tok = None
        self.created_users, self.created_roles, self.created_client = [], [], None
        self.orig_direct = None
        self.admin_client_id = None

    def master(self):
        s, b = http("POST", f"{KC}/realms/master/protocol/openid-connect/token",
                    form={"client_id": "admin-cli", "username": "admin", "password": "admin", "grant_type": "password"})
        assert s == 200, f"master token {s} {b}"
        return {"Authorization": "Bearer " + b["access_token"]}

    def call(self, method, path, body=None):
        return http(method, f"{KC}/admin/realms/cce{path}", body=body, headers=self.master())

    def enable_direct_grant(self):
        s, b = self.call("GET", "/clients?clientId=cce-admin-ui")
        c = b[0]
        self.admin_client_id, self.orig_direct = c["id"], c.get("directAccessGrantsEnabled", False)
        c["directAccessGrantsEnabled"] = True
        s, _ = self.call("PUT", f"/clients/{c['id']}", c)
        assert s == 204, f"enable direct grant {s}"

    def restore_direct_grant(self):
        if self.admin_client_id is None:
            return
        s, c = self.call("GET", f"/clients/{self.admin_client_id}")
        c["directAccessGrantsEnabled"] = self.orig_direct
        self.call("PUT", f"/clients/{self.admin_client_id}", c)

    def make_user(self, username, email, password="E2e-Pass-1!"):
        s, _ = self.call("POST", "/users", {"username": username, "email": email, "firstName": "E2E", "lastName": username,
                                            "enabled": True, "emailVerified": True,
                                            "credentials": [{"type": "password", "value": password, "temporary": False}]})
        assert s == 201, f"kc create user {s}"
        uid = self.user_id(username)
        self.created_users.append(uid)
        return uid

    def user_id(self, username):
        s, b = self.call("GET", f"/users?username={urllib.parse.quote(username)}&exact=true")
        return b[0]["id"] if s == 200 and b else None

    def set_password(self, uid, password="E2e-Pass-1!"):
        s, _ = self.call("PUT", f"/users/{uid}/reset-password", {"type": "password", "value": password, "temporary": False})
        # also clear any required actions the console's invite set (verify email / update password)
        s2, u = self.call("GET", f"/users/{uid}")
        u.update({"requiredActions": [], "emailVerified": True, "enabled": True})
        self.call("PUT", f"/users/{uid}", u)
        return s == 204

    def realm_roles_of(self, uid):
        s, b = self.call("GET", f"/users/{uid}/role-mappings/realm")
        return sorted(r["name"] for r in b) if s == 200 else None

    def token(self, username, password="E2e-Pass-1!"):
        s, b = http("POST", f"{KC}/realms/cce/protocol/openid-connect/token",
                    form={"client_id": "cce-admin-ui", "grant_type": "password", "username": username, "password": password, "scope": "openid email"})
        return b["access_token"] if s == 200 else None, s, b

    def delete_user(self, uid):
        self.call("DELETE", f"/users/{uid}")

    def delete_realm_role(self, name):
        self.call("DELETE", f"/roles/{urllib.parse.quote(name)}")


kc = Keycloak()


def bearer(t):
    return {"Authorization": "Bearer " + t}


def api(method, path, token, body=None, base=ADMIN):
    return http(method, f"{base}{path}", body=body, headers=bearer(token))


# ---------------------------------------------------------------- gateways
class Gateway:
    def __init__(self, port, claim_path=None):
        self.port, self.claim_path, self.proc = port, claim_path, None

    def start(self):
        env = dict(os.environ)
        env.update({
            "SERVER_PORT": str(self.port),
            "DATABASE_URL": "r2dbc:postgresql://localhost:5432/ccedb", "DATABASE_USERNAME": "cce_user", "DATABASE_PASSWORD": "cce_pass",
            "JWT_ISSUER": "http://localhost:8081/realms/cce", "JWT_AUDIENCE": "account",
            "KEYCLOAK_SERVER_URL": KC, "KEYCLOAK_REALM": "master", "KEYCLOAK_TARGET_REALM": "cce",
            "KEYCLOAK_ADMIN_USERNAME": "admin", "KEYCLOAK_ADMIN_PASSWORD": "admin",
            "ADMIN_SERVICE_URI": ADMIN, "LOAD_PERMISSIONS_FROM_DATABASE_ON_STARTUP": "true",
            "ALLOWED_ORIGINS": "http://localhost:3002",
        })
        if self.claim_path:
            env["JWT_ROLES_CLAIM_PATH"] = self.claim_path
        R = "SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_ROUTES"
        for i, (rid, uri, path) in enumerate([("admin-service", ADMIN, "/admin/**"), ("protocol-service", "http://localhost:8091", "/v1/protocol/**"),
                                              ("insights-service", "http://localhost:8093", "/v1/insights/**")]):
            env.update({f"{R}_{i}_ID": rid, f"{R}_{i}_URI": uri, f"{R}_{i}_PREDICATES_0": f"Path={path}", f"{R}_{i}_FILTERS_0_NAME": "JwtValidationFilter"})
        self.proc = subprocess.Popen(["java", "-jar", JAR], env=env, stdout=open(f"/tmp/e2e-gw-{self.port}.log", "w"), stderr=subprocess.STDOUT)
        for _ in range(60):
            s, _ = http("GET", f"http://localhost:{self.port}/nothing")
            if s != 0:
                return True
            time.sleep(2)
        return False

    def reload(self):
        return http("POST", f"http://localhost:{self.port}/api/permissions/sync-to-keycloak", timeout=60)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(10)
            except Exception:
                self.proc.kill()


# ---------------------------------------------------------------- scenarios
class Ctx:
    pass


c = Ctx()


def audit_count(actor_id, action=None):
    q = f"select count(*) from admin_audit_log where actor_id='{actor_id}'" + (f" and action='{action}'" if action else "")
    return int(psql(q)[0])


def bootstrap():
    """One SUPER_ADMIN to drive the console API with (provisioned by hand, like in a real environment)."""
    uname = f"e2e-super-{RUN}"
    email = f"{uname}@example.org"
    kc.make_user(uname, email)
    uid = str(uuid.uuid4())
    psql(f"insert into users (id,username,email,first_name,last_name,status) values ('{uid}','{uname}','{email}','E2E','Super','ACTIVE')")
    psql(f"insert into user_roles (user_id,role_id) select '{uid}', id from roles where name='SUPER_ADMIN'")
    s, b = http("GET", f"{KC}/admin/realms/cce/roles/SUPER_ADMIN", headers=kc.master())
    assert s == 200, "realm role SUPER_ADMIN missing in Keycloak"
    http("POST", f"{KC}/admin/realms/cce/users/{kc.user_id(uname)}/role-mappings/realm", body=[b], headers=kc.master())
    c.super_name, c.super_email, c.super_id = uname, email, uid
    tok, s, b = kc.token(uname)
    assert tok, f"super token {s} {b}"
    c.sup = tok


def sup():
    t, s, b = kc.token(c.super_name)
    return t


def create_role(name, menus=(), perms=()):
    s, b = api("POST", "/admin/v1/roles", sup(), {"name": name, "displayName": name.title(), "description": "e2e"})
    if s != 201:
        return None, f"create role {s} {b}"
    rid = b["data"]["id"]
    kc.created_roles.append(name)
    if menus:
        s, b2 = api("PUT", f"/admin/v1/roles/{rid}/menus", sup(), {"menuIds": [menu_id(k) for k in menus]})
        if s != 200:
            return rid, f"menus {s} {b2}"
    s, b3 = api("PUT", f"/admin/v1/roles/{rid}/permissions", sup(), {"permissions": [dict(httpMethod=m, uriPattern=u, description="e2e", category="E2E") for m, u in perms]})
    return rid, (s, b3)


def menu_id(key):
    return psql(f"select id from menus where menu_key='{key}'")[0]


def role_id(name):
    return psql(f"select id from roles where name='{name}'")[0]


ME_ROWS = [("GET", "/admin/v1/me"), ("GET", "/admin/v1/me/menus")]


def create_user(prefix, role_ids):
    uname = f"e2e-{prefix}-{RUN}"
    email = f"{uname}@example.org"
    s, b = api("POST", "/admin/v1/users", sup(), {"username": uname, "email": email, "firstName": "E2E", "lastName": prefix, "roleIds": role_ids})
    return uname, email, s, b


def s_setup():
    print("\n== setup")
    check("setup", "direct-grant switch on cce-admin-ui turned on for the run", True)
    check("setup", "SUPER_ADMIN bootstrap user can call /me", api("GET", "/admin/v1/me", c.sup)[0] == 200)
    c.gwa, c.gwb = Gateway(8190), Gateway(8191, "resource_access.gateway-service.roles")
    check("setup", "test gateway A (realm_access.roles) up on 8190", c.gwa.start())
    check("setup", "test gateway B (resource_access.gateway-service.roles) up on 8191", c.gwb.start())


def s1():
    print("\n== S1 sidebar for a real role")
    rid, res = create_role(f"E2E_S1_{RUN.upper()}", menus=["dashboard", "facilities"], perms=ME_ROWS + [("GET", "/v1/insights/**")])
    check("S1", "role with 2 insights menus and 3 permission rows saved + synced", rid and res[0] == 200, res)
    c.gwa.reload()
    uname, email, s, b = create_user("s1", [rid])
    check("S1", "user created with that role", s == 201, b)
    kc.set_password(kc.user_id(uname))
    tok, _, _ = kc.token(uname)
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok)
    labels = [m["label"] for m in b.get("data", [])] if s == 200 else b
    check("S1", "GET /me/menus (service direct) returns exactly the role's 2 menus", s == 200 and labels == ["Dashboard", "Facilities"], labels)
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok, base=GW_A)
    check("S1", "same call through the gateway (realm-role path) works", s == 200 and len(b.get("data", [])) == 2, f"{s} {b}")
    s, b = api("GET", "/admin/v1/me/menus?console=ADMIN", c.sup)
    check("S1", "SUPER_ADMIN's admin console sidebar has the 4 admin tabs", s == 200 and [m["label"] for m in b["data"]] == ["Users", "Roles", "Menus", "Protocols"], b)
    c.s1 = dict(rid=rid, uname=uname, email=email)


def s2_s3_s4_s5():
    print("\n== S2 onboarding, S3 role config, S4 role change, S5 deactivation")
    # S3 first (S2 needs the role)
    before = audit_count(c.super_id)
    name_a, name_b = f"E2E_A_{RUN.upper()}", f"E2E_B_{RUN.upper()}"
    s, b = api("POST", "/admin/v1/roles", sup(), {"name": name_a, "displayName": "E2E A"})
    check("S3", "role created (201)", s == 201, b)
    ridA = b["data"]["id"]; kc.created_roles.append(name_a)
    s, b = api("PUT", f"/admin/v1/roles/{ridA}/menus", sup(), {"menuIds": [menu_id("dashboard")]})
    check("S3", "menus saved", s == 200, b)
    s, b = api("PUT", f"/admin/v1/roles/{ridA}/permissions", sup(), {"permissions": [dict(httpMethod=m, uriPattern=u, description="e2e", category="E2E") for m, u in ME_ROWS]})
    check("S3", "permissions saved and gateway sync SYNCED", s == 200 and b.get("data", b).get("syncStatus") == "SYNCED" if s == 200 else False, f"{s} {b}")
    check("S3", "role exists in Keycloak as a realm role after the sync", kc.call("GET", f"/roles/{name_a}")[0] == 200)
    s, b = api("POST", "/admin/v1/roles", sup(), {"name": name_b, "displayName": "E2E B"}); kc.created_roles.append(name_b)
    ridB = b["data"]["id"]
    s, b = api("PUT", f"/admin/v1/roles/{ridB}/permissions", sup(), {"permissions": [dict(httpMethod="GET", uriPattern="/admin/v1/me", description="e2e", category="E2E")]})
    after = audit_count(c.super_id)
    check("S3", "each of the 5 mutating role calls wrote exactly one audit row", after - before == 5, f"delta={after - before}")
    # S2
    before = audit_count(c.super_id)
    uname, email, s, b = create_user("s2", [ridA])
    check("S2", "POST /users returns 201 with the role", s == 201 and b["data"]["roles"] == [name_a], f"{s} {b}")
    kuid = kc.user_id(uname)
    check("S2", "Keycloak account created", kuid is not None)
    check("S2", "Keycloak realm role mapped", kuid and name_a in (kc.realm_roles_of(kuid) or []), kc.realm_roles_of(kuid) if kuid else None)
    check("S2", "local users row + user_roles row exist", psql(f"select count(*) from users u join user_roles ur on ur.user_id=u.id where u.email='{email}'")[0] == "1")
    check("S2", "exactly one USER_CREATED audit row", audit_count(c.super_id) - before == 1 and audit_count(c.super_id, "USER_CREATED") >= 1)
    if kuid:
        kc.created_users.append(kuid)
        kc.set_password(kuid)
    # duplicate email: zero side effects
    s, b = api("POST", "/admin/v1/users", sup(), {"username": uname + "x", "email": email, "firstName": "x", "lastName": "x", "roleIds": [ridA]})
    check("S2", "duplicate email refused with 409", s == 409, f"{s} {b}")
    uid = b = None
    uid = psql(f"select id from users where email='{email}'")[0]
    # S4
    before = audit_count(c.super_id)
    s, b = api("PUT", f"/admin/v1/users/{uid}/roles", sup(), {"roleIds": [ridB]})
    check("S4", "role replaced A -> B (200)", s == 200, f"{s} {b}")
    roles = kc.realm_roles_of(kuid) or []
    check("S4", "Keycloak: A removed, B added, other mappings untouched", name_a not in roles and name_b in roles and "default-roles-cce" in roles, roles)
    check("S4", "one USER_ROLES_UPDATED audit row", audit_count(c.super_id) - before == 1)
    s, b = api("PUT", f"/admin/v1/users/{uid}/roles", sup(), {"roleIds": [ridB]})
    check("S4", "same set again is a no-op: 200 and no new audit row", s == 200 and audit_count(c.super_id) - before == 1)
    # S5
    before = audit_count(c.super_id)
    s, b = api("PATCH", f"/admin/v1/users/{uid}/status", sup(), {"status": "INACTIVE"})
    check("S5", "deactivate (200)", s == 200, f"{s} {b}")
    s, u = kc.call("GET", f"/users/{kuid}")
    check("S5", "Keycloak account disabled", s == 200 and u.get("enabled") is False)
    check("S5", "role assignments kept", psql(f"select count(*) from user_roles where user_id='{uid}'")[0] == "1")
    tok, st, _ = kc.token(uname)
    check("S5", "disabled user can no longer get a token", tok is None, st)
    check("S5", "one USER_DEACTIVATED audit row", audit_count(c.super_id) - before == 1)
    s, b = api("PATCH", f"/admin/v1/users/{uid}/status", sup(), {"status": "ACTIVE"})
    check("S5", "reactivation restores access without re-selecting roles", s == 200 and kc.token(uname)[0] is not None, f"{s}")
    c.s2 = dict(uname=uname, uid=uid, kuid=kuid, ridB=ridB, name_b=name_b)


def s7_s8():
    print("\n== S7 protocol access, S8 limited user is limited")
    rows = ME_ROWS + [("GET", "/v1/protocol/protocol-definitions/**"), ("POST", "/v1/protocol/protocol-definitions/**")]
    name = f"E2E_PROTO_{RUN.upper()}"
    s, b = api("POST", "/admin/v1/roles", sup(), {"name": name, "displayName": "E2E protocols"}); kc.created_roles.append(name)
    rid = b["data"]["id"]
    s1, _ = api("PUT", f"/admin/v1/roles/{rid}/menus", sup(), {"menuIds": [menu_id("admin-protocols")]})
    s2, b2 = api("PUT", f"/admin/v1/roles/{rid}/permissions", sup(), {"permissions": [dict(httpMethod=m, uriPattern=u, description="e2e", category="E2E") for m, u in rows]})
    check("S7", "custom role with the Protocols tab and the 4 permission rows saved + synced", s1 == 200 and s2 == 200, f"{s1} {s2} {b2}")
    c.gwa.reload()
    uname, email, s, b = create_user("s7", [rid])
    kuid = kc.user_id(uname); kc.created_users.append(kuid); kc.set_password(kuid)
    tok, _, _ = kc.token(uname)
    s, b = api("GET", "/admin/v1/me/menus?console=ADMIN", tok)
    check("S7", "signed-in user's admin sidebar is ONLY Protocols", s == 200 and [m["label"] for m in b["data"]] == ["Protocols"], b)
    s, b = api("GET", "/v1/protocol/protocol-definitions", tok, base=GW_A)
    check("S7", "GET protocols through the gateway is allowed", s == 200, f"{s} {str(b)[:120]}")
    s, b = http("POST", f"{GW_A}/v1/protocol/protocol-definitions", body={"resourceType": "PlanDefinition"}, headers=bearer(tok))
    check("S7", "POST protocols is allowed by the gateway (answer is NOT 401/403; an invalid body is rejected by the protocol service itself)", s not in (401, 403, 0), f"{s} {str(b)[:120]}")
    # S8
    for path in ("/admin/v1/users", "/admin/v1/roles", "/admin/v1/menus"):
        s, b = api("GET", path, tok)
        check("S8", f"service refuses {path} for the limited user even with the gateway bypassed", s == 403, f"{s} {str(b)[:100]}")
        s, b = api("GET", path, tok, base=GW_A)
        check("S8", f"gateway refuses {path} for the limited user", s in (401, 403), f"{s} {str(b)[:100]}")
    c.s7 = dict(rid=rid, uname=uname)


def s9():
    print("\n== S9 role-aware Insights")
    name = f"E2E_S9_{RUN.upper()}"
    rid, res = create_role(name, menus=["dashboard"], perms=ME_ROWS + [("GET", "/v1/insights/**")])
    c.gwa.reload()
    uname, email, s, b = create_user("s9", [rid])
    kuid = kc.user_id(uname); kc.created_users.append(kuid); kc.set_password(kuid)
    tok, _, _ = kc.token(uname)
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok)
    check("S9", "user holding only Dashboard gets exactly [Dashboard]", s == 200 and [m["label"] for m in b["data"]] == ["Dashboard"], b)
    s, b = api("GET", "/v1/insights/lookups/protocols", tok, base=GW_A)
    check("S9", "an Insights data call through the gateway works for that role", s == 200 and "data" in b, f"{s} {str(b)[:100]}")
    s, b = api("PUT", f"/admin/v1/roles/{rid}/menus", sup(), {"menuIds": [menu_id("dashboard"), menu_id("events")]})
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok)  # SAME token: no re-login
    check("S9", "after an admin ticks Events, the SAME token sees [Dashboard, Events] (database, not the login ticket)", s == 200 and [m["label"] for m in b["data"]] == ["Dashboard", "Events"], b)
    api("PUT", f"/admin/v1/roles/{rid}/menus", sup(), {"menuIds": []})
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok)
    check("S9", "a user whose role has no menus gets an empty list (the UI shows 'No access')", s == 200 and b["data"] == [], b)
    c.s9 = dict(rid=rid, uname=uname, kuid=kuid)


def failures():
    print("\n== failure paths")
    # user in Keycloak but not in the admin users table
    uname = f"e2e-kconly-{RUN}"
    kuid = kc.make_user(uname, f"{uname}@example.org")
    tok, _, _ = kc.token(uname)
    s, b = api("GET", "/admin/v1/me", tok)
    check("F1", "a Keycloak-only user (not in admin users table) is refused by /me (Q4)", s == 403, f"{s} {str(b)[:100]}")
    # role without the /me rows
    name = f"E2E_NOROWS_{RUN.upper()}"
    rid, res = create_role(name, menus=["dashboard"], perms=[("GET", "/v1/insights/**")])
    c.gwa.reload()
    uname2, _, s, b = create_user("norows", [rid])
    k2 = kc.user_id(uname2); kc.created_users.append(k2); kc.set_password(k2)
    tok2, _, _ = kc.token(uname2)
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok2, base=GW_A)
    check("F2", "a role WITHOUT the /me permission rows is refused at the gateway (empty sidebar in the UI)", s in (401, 403), f"{s} {str(b)[:100]}")
    s, b = api("GET", "/admin/v1/me/menus?console=INSIGHTS", tok2)
    check("F2", "the same call straight to the admin service works (why local never shows the problem)", s == 200, f"{s}")
    # unknown route, unauthenticated, CORS
    s, b = http("GET", f"{GW_A}/v1/does-not-exist", headers=bearer(tok2))
    note("F3", "unknown gateway route answers", f"{s} {str(b)[:80]}")
    s, b = http("GET", f"{GW_A}/admin/v1/me")
    note("F3", "no token at all answers", f"{s} {str(b)[:80]}")
    s, b = http("POST", f"{GW_A}/admin/v1/users", body={}, headers={"Origin": "http://evil.example", **bearer(tok2)})
    check("F4", "a browser POST from an origin not in ALLOWED_ORIGINS is refused", s == 403, f"{s} {str(b)[:80]}")
    s, b = http("POST", f"{GW_A}/admin/v1/users", body={}, headers={"Origin": "http://localhost:3002", **bearer(tok2)})
    check("F4", "the same POST from an allowed origin is judged on its token/permissions instead (not blocked by CORS)", s != 0, f"{s}")


def q3():
    print("\n== Q3 production-style claim path")
    uname, kuid, rname = c.s1["uname"], kc.user_id(c.s1["uname"]), f"E2E_S1_{RUN.upper()}"
    tok, _, _ = kc.token(uname)
    import base64
    claims = json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "==="))
    note("Q3", "token claims of a console-created user", {"realm_access": claims.get("realm_access"), "resource_access": list((claims.get("resource_access") or {}).keys())})
    c.gwb.reload()  # gateway B started before the test roles existed: make it read the permission rows
    s, b = api("GET", "/v1/insights/lookups/protocols", tok, base=GW_A)
    check("Q3", "gateway A (realm roles, local default): the console-assigned role works", s == 200, f"{s}")
    s, b = api("GET", "/v1/insights/lookups/protocols", tok, base=GW_B)
    check("Q3", "gateway B (client roles, as deployed in k8s): the SAME user is refused", s in (401, 403), f"{s} {str(b)[:100]}")
    # now give the user the CLIENT role on gateway-service
    s, cl = kc.call("GET", "/clients?clientId=gateway-service")
    if not cl:
        s, _ = kc.call("POST", "/clients", {"clientId": "gateway-service", "enabled": True, "publicClient": False, "bearerOnly": True})
        s, cl = kc.call("GET", "/clients?clientId=gateway-service")
        kc.created_client = cl[0]["id"]
        note("Q3", "client gateway-service did not exist locally", "created for the test, removed afterwards")
    cid = cl[0]["id"]
    kc.call("POST", f"/clients/{cid}/roles", {"name": rname})
    s, role = kc.call("GET", f"/clients/{cid}/roles/{rname}")
    kc.call("POST", f"/users/{kuid}/role-mappings/clients/{cid}", [role])
    tok, _, _ = kc.token(uname)
    claims = json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "==="))
    note("Q3", "after adding the client role, token has", (claims.get("resource_access") or {}).get("gateway-service"))
    s, b = api("GET", "/v1/insights/lookups/protocols", tok, base=GW_B)
    check("Q3", "gateway B: with the client role on gateway-service the user is allowed", s == 200, f"{s} {str(b)[:100]}")
    kc.call("DELETE", f"/clients/{cid}/roles/{rname}")


def s6(skip):
    print("\n== S6 sync failure and re-sync")
    if skip:
        note("S6", "skipped", "--skip-s6")
        return
    out = subprocess.run(["ss", "-ltnp", "sport = :8090"], capture_output=True, text=True).stdout
    import re
    m = re.search(r"pid=(\d+)", out)
    if not m:
        note("S6", "skipped", "no gateway on 8090")
        return
    pid = int(m.group(1))
    env = dict(l.split("=", 1) for l in open(f"/proc/{pid}/environ").read().split("\0") if "=" in l)
    cwd = os.readlink(f"/proc/{pid}/cwd")
    cmd = open(f"/proc/{pid}/cmdline").read().split("\0")[:-1]
    rid = c.s1["rid"]
    os.kill(pid, signal.SIGTERM)
    time.sleep(4)
    perms = [dict(httpMethod=m_, uriPattern=u, description="e2e", category="E2E") for m_, u in ME_ROWS + [("GET", "/v1/insights/**"), ("GET", "/v1/insights/events/**")]]
    s, b = api("PUT", f"/admin/v1/roles/{rid}/permissions", sup(), {"permissions": perms})
    check("S6", "gateway down: permission save answers 502 KEYCLOAK_SYNC_FAILED", s == 502 and b.get("error", {}).get("code") == "KEYCLOAK_SYNC_FAILED", f"{s} {str(b)[:120]}")
    check("S6", "rows stayed saved in the database despite the failed sync", psql(f"select count(*) from api_permissions p join roles r on r.name=p.permission_name where r.id='{rid}'")[0] == "4")
    # restart the developer's gateway exactly as it was
    subprocess.Popen(cmd, cwd=cwd, env=env, stdout=open("/tmp/e2e-gw-8090.log", "w"), stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(60):
        if http("GET", f"{GW_MAIN}/nothing")[0] != 0:
            break
        time.sleep(2)
    s, b = api("POST", f"/admin/v1/roles/{rid}/sync", sup())
    check("S6", "gateway back: POST /roles/:id/sync re-syncs successfully", s == 200, f"{s} {str(b)[:120]}")


def cleanup():
    print("\n== cleanup")
    for g in (getattr(c, "gwa", None), getattr(c, "gwb", None)):
        if g:
            g.stop()
    try:
        emails = "'" + "','".join([f"e2e-%-{RUN}@example.org"]) + "'"
        ids = psql(f"select id from users where email like 'e2e-%-{RUN}@example.org'")
        idl = ",".join(f"'{i}'" for i in ids)
        if idl:
            psql(f"delete from admin_audit_log where actor_id in ({idl})")
            psql(f"delete from user_roles where user_id in ({idl})")
            psql(f"delete from users where id in ({idl})")
        names = ",".join(f"'{n}'" for n in kc.created_roles)
        if names:
            psql(f"delete from api_permissions where permission_name in ({names})")
            psql(f"delete from role_menus where role_id in (select id from roles where name in ({names}))")
            psql(f"delete from roles where name in ({names})")
    except Exception as e:
        print("  db cleanup problem:", e)
    for uid in kc.created_users:
        kc.delete_user(uid)
    for n in kc.created_roles:
        kc.delete_realm_role(n)
    if kc.created_client:
        kc.call("DELETE", f"/clients/{kc.created_client}")
    kc.restore_direct_grant()
    s, cl = kc.call("GET", "/clients?clientId=cce-admin-ui")
    print("  direct access grants on cce-admin-ui restored to:", cl[0].get("directAccessGrantsEnabled"))
    http("POST", f"{GW_MAIN}/api/permissions/sync-to-keycloak", timeout=60)  # reload the main gateway without the e2e rows
    left = psql(f"select (select count(*) from users where email like 'e2e-%'), (select count(*) from roles where name like 'E2E_%'), (select count(*) from api_permissions where permission_name like 'E2E_%')")
    print("  leftovers (users|roles|permission rows):", left)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-s6", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args()
    t0 = time.time()
    try:
        kc.enable_direct_grant()
        bootstrap()
        s_setup(); s1(); s2_s3_s4_s5(); s7_s8(); s9(); failures(); q3(); s6(a.skip_s6)
    finally:
        cleanup()
    ok = sum(1 for r in RESULTS if r["ok"] is True); bad = [r for r in RESULTS if r["ok"] is False]
    print(f"\n{ok} passed, {len(bad)} failed, {sum(1 for r in RESULTS if r['ok'] is None)} informational  ({time.time() - t0:.0f}s)")
    for r in bad:
        print("  FAILED:", r["scenario"], r["check"], "->", r["detail"])
    if a.json:
        json.dump(RESULTS, open(a.json, "w"), indent=2)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
