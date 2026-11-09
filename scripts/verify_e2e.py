#!/usr/bin/env python3
"""Builds and runs the full stack, exercises the order flow, and stops the stack.

Exits with a nonzero status if any check fails.
"""
import base64
import collections
import json
import os
import pathlib
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = "http://localhost:8085"
ORDER_COUNT = 20
JWT_KEY = base64.b64encode(secrets.token_bytes(40)).decode()
ENV = {**os.environ, "JWT_SECRET": JWT_KEY}

failures = []


def sh(command):
    return subprocess.run(command, shell=True, cwd=ROOT, env=ENV, capture_output=True, text=True)


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        GATEWAY + path, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()
    except OSError as error:
        return 0, str(error)


def check(name, passed, detail=""):
    print(f"{'PASS' if passed else 'FAIL'}  {name}  {detail}".rstrip())
    if not passed:
        failures.append(name)


def wait_until(condition, attempts=120, delay=3):
    for _ in range(attempts):
        if condition():
            return True
        time.sleep(delay)
    return False


def run_checks():
    routes = ["/api/users", "/api/products", "/api/orders"]
    ready = wait_until(lambda: all(call("GET", route)[0] == 200 for route in routes))
    check("gateway routes respond", ready)
    if not ready:
        return

    user_body = {"name": "E2E", "email": "e2e@example.com", "password": "pw-e2e-123"}
    status, raw = call("POST", "/api/users", user_body)
    user = json.loads(raw)
    check("create user hides password", status == 200 and "password" not in user, str(status))

    status, raw = call("POST", "/api/users/login", {"email": user_body["email"], "password": user_body["password"]})
    check("login with correct password", status == 200 and "token" in raw, str(status))
    status, raw = call("POST", "/api/users/login", {"email": user_body["email"], "password": "wrong"})
    check("login with wrong password has no token", "token" not in raw, str(status))

    status, raw = call("POST", "/api/products",
                       {"name": "Widget", "description": "d", "price": 100, "stock": 5, "category": "c"})
    product = json.loads(raw)
    check("create product", status == 200, str(status))

    unknown_user = {"userId": 99999, "productId": product["id"], "quantity": 1}
    # order-service caches the Eureka registry, so it finds other services after a delay.
    wait_until(lambda: "Service Instance cannot be null" not in call("POST", "/api/orders", unknown_user)[1], 60)
    time.sleep(40)

    status, raw = call("POST", "/api/orders", unknown_user)
    check("order for missing user returns 404", status == 404, raw[:60])
    status, raw = call("POST", "/api/orders", {"userId": user["id"], "productId": 99999, "quantity": 1})
    check("order for missing product returns 404", status == 404, raw[:60])
    check("payment without JWT is rejected", call("POST", "/api/payments", {"orderId": "1"})[0] in (401, 403))

    order = {"userId": user["id"], "productId": product["id"], "quantity": 1}
    responses = [call("POST", "/api/orders", order) for _ in range(ORDER_COUNT)]
    check("all orders accepted", all(status == 200 for status, _ in responses),
          str(collections.Counter(status for status, _ in responses)))

    terminal = {"PAYMENT_SUCCESS", "PAYMENT_FAILED"}

    def statuses():
        return collections.Counter(item["status"] for item in json.loads(call("GET", "/api/orders")[1]))

    settled = wait_until(lambda: set(statuses()) <= terminal, attempts=20, delay=2)
    check("all orders reach a terminal status", settled, str(statuses()))

    order_logs = sh("docker compose logs order-service").stdout
    for text in ["Payment service unreachable", "unknown order id", "Row was updated", "Failed to parse"]:
        check(f"order-service log has no '{text}'", order_logs.count(text) == 0, str(order_logs.count(text)))

    for service in ["user-service", "order-service", "payment-service"]:
        value = sh(f"docker compose exec -T {service} printenv JWT_SECRET").stdout.strip()
        check(f"{service} receives JWT_SECRET", value == JWT_KEY)

    payment_logs = sh("docker compose logs payment-service").stdout
    # The default log pattern keeps only the last 15 characters of a thread name.
    mock_payment_lines = [line for line in payment_logs.splitlines() if "Mocking payment" in line]
    check("payment-service handles requests on virtual threads",
          bool(mock_payment_lines) and all("-handler-" in line for line in mock_payment_lines),
          mock_payment_lines[0][40:110] if mock_payment_lines else "no payment log lines")

    exception_lines = [line for line in payment_logs.splitlines()
                       if "Exception" in line]
    print(f"INFO  payment-service log lines containing Exception: {len(exception_lines)}")
    for line in exception_lines:
        print(f"INFO    {line[:220]}")

    for line in sh("docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'").stdout.splitlines():
        print(f"INFO  memory {line}")


def main():
    built = sh("docker compose --progress=plain up -d --build")
    if built.returncode:
        output = (built.stdout + built.stderr).splitlines()
        print("\n".join(line[:240] for line in output if "ERROR" in line or "Could not" in line)[-4000:])
        sys.exit("docker compose up failed")
    try:
        run_checks()
    finally:
        print("stopped" if sh("docker compose down").returncode == 0 else "docker compose down failed")
    if failures:
        sys.exit(f"{len(failures)} check(s) failed: {failures}")
    print("All checks passed")


if __name__ == "__main__":
    main()
