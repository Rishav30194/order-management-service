#!/usr/bin/env python3
"""Replays dead-lettered payment events from payment-events.DLT back to payment-events.

Run it after fixing whatever made order-service reject the events. Each run replays only the events
dead-lettered since the previous run, because it reads through the dlt-replay consumer group. Events
are keyed by orderId, as payment-service publishes them. Replaying is safe to repeat: order-service
applies only allowed status transitions, so an event that was already applied changes nothing.

Usage:
    python3 scripts/replay_dlt.py            # replay new dead-lettered events
    python3 scripts/replay_dlt.py --dry-run  # list every dead-lettered event without replaying or
                                             # moving the consumer group
"""
import argparse
import json
import logging
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
KAFKA_BIN = "/opt/kafka/bin"
BOOTSTRAP = "localhost:9092"
DEAD_LETTER_TOPIC = "payment-events.DLT"
TOPIC = "payment-events"
REPLAY_GROUP = "dlt-replay"
KEY_SEPARATOR = "\t"

log = logging.getLogger("replay_dlt")


def kafka(script: str, args: list[str], stdin: str | None = None) -> subprocess.CompletedProcess[str]:
    command = ["docker", "compose", "exec", "-T", "kafka", f"{KAFKA_BIN}/{script}", "--bootstrap-server", BOOTSTRAP, *args]
    return subprocess.run(command, cwd=ROOT, input=stdin, capture_output=True, text=True)


def read_dead_letters(dry_run: bool, timeout_ms: int) -> list[str]:
    args = ["--topic", DEAD_LETTER_TOPIC, "--from-beginning", "--timeout-ms", str(timeout_ms)]
    # Without a group, the console consumer uses a throwaway one, so a dry run doesn't move the replay group.
    if not dry_run:
        args += ["--group", REPLAY_GROUP]
    result = kafka("kafka-console-consumer.sh", args)
    # The consumer exits with an error status when --timeout-ms expires, which is the normal way it stops.
    if "TimeoutException" not in result.stderr and result.returncode != 0:
        raise RuntimeError(f"reading {DEAD_LETTER_TOPIC} failed: {result.stderr.strip()}")
    return [line for line in result.stdout.splitlines() if line.strip()]


def keyed(event: str) -> str | None:
    try:
        order_id = json.loads(event)["orderId"]
    except (ValueError, KeyError, TypeError):
        return None
    return f"{order_id}{KEY_SEPARATOR}{event}"


def replay(events: list[str]) -> None:
    lines = []
    for event in events:
        line = keyed(event)
        if line is None:
            log.warning("Skipping event with no orderId: %s", event)
        else:
            lines.append(line)
    if not lines:
        return
    args = ["--topic", TOPIC, "--property", "parse.key=true", "--property", f"key.separator={KEY_SEPARATOR}"]
    result = kafka("kafka-console-producer.sh", args, stdin="\n".join(lines) + "\n")
    if result.returncode != 0:
        # The replay group has already moved past these events, so log them in full for a manual retry.
        for line in lines:
            log.error("Not replayed: %s", line.split(KEY_SEPARATOR, 1)[1])
        raise RuntimeError(f"publishing to {TOPIC} failed: {result.stderr.strip()}")
    log.info("Replayed %d events to %s", len(lines), TOPIC)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="list events without replaying them")
    parser.add_argument("--timeout-ms", type=int, default=10000, help="stop reading after this long with no new event")
    options = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s  %(message)s")

    try:
        events = read_dead_letters(options.dry_run, options.timeout_ms)
        log.info("Found %d dead-lettered events", len(events))
        if options.dry_run:
            for event in events:
                log.info("  %s", event)
        else:
            replay(events)
    except RuntimeError as error:
        log.error("%s", error)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
