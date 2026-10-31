"""Publish Ariel's alerts to a (self-hosted) ntfy server.

ntfy (https://ntfy.sh) is a topic-based pub/sub push service: every device subscribed to a topic in
the ntfy app gets what is published to it. It brings panics to iPhones whose Ariel cannot have push
(builds signed by a free Apple team), even with Ariel closed. README, "iPhone notifications through
ntfy", has the setup.

Configuration is read from the environment on every call, so that a misconfigured relay can be fixed
with a container restart and tests can monkeypatch it:

  NTFY_BASE_URL         URL the relay uses to reach ntfy (required to enable)
  NTFY_PUBLIC_URL       URL devices use to subscribe (defaults to NTFY_BASE_URL)
  NTFY_TOKEN            Access token (tk_...) of a publish-capable ntfy user
  NTFY_USERNAME/_PASSWORD  Basic-auth alternative to NTFY_TOKEN
  NTFY_TIMEOUT_SECONDS  Per-request HTTP timeout (default 5)

iOS delivery check. The ntfy iOS app is only woken through ntfy.sh: the ntfy server forwards a "poll
request" there for every message (its upstream-base-url), once and without retrying, and a forward
that fails loses the notification. So the relay listens on the upstream server while it publishes,
and sends the poll request itself when the ntfy server's has not arrived in time:

  NTFY_UPSTREAM_URL           The ntfy server's upstream-base-url (default https://ntfy.sh; "off"
                              turns the check off)
  NTFY_UPSTREAM_TOKEN         Optional ntfy.sh access token for the relay's own poll requests
                              (higher rate limits)
  NTFY_UPSTREAM_WAIT_SECONDS  How long to wait for the ntfy server's poll request before sending it
                              (default 15)
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import re
import secrets
import socket
import string
import threading
import time
import urllib.error
import urllib.request
from typing import Iterable, NamedTuple, Optional, Tuple

LOGGER = logging.getLogger("ariel-relay")

# The relay publishes for anyone who can reach it, so it only ever publishes in Ariel's own namespace,
# whatever else its ntfy user may write to. Within it, ntfy's own rule: 1-64 characters of letters,
# digits, '-' and '_'.
TOPIC_PREFIX = "ariel_"
TOPIC_PATTERN = re.compile(r"^ariel_[-_A-Za-z0-9]{1,58}$")

# ntfy priorities: 1=min, 2=low, 3=default, 4=high, 5=max. The iOS app makes 4 Time Sensitive, and 5
# too, or a Critical Alert from version 1.8 for those who turn that on in its settings.
PRIORITY_HIGH = 4
PRIORITY_MAX = 5

# An alert is worth a few tries: ntfy restarting must not lose a panic.
PUBLISH_ATTEMPTS = 3

# The notification waits for the upstream server to answer, so it does not get long.
UPSTREAM_OPEN_TIMEOUT_SECONDS = 2.0

_GENERATED_TOPIC_ALPHABET = string.ascii_letters + string.digits

_UPSTREAM_OFF = {"off", "none", "no", "false", "0"}

# What the upstream check reports for one notification.
UPSTREAM_CONFIRMED = "confirmed"  # the ntfy server's poll request reached the upstream server
UPSTREAM_FORWARDED = "forwarded"  # it did not; the relay sent it instead
UPSTREAM_FAILED = "failed"  # it did not, and the relay could not send it either
UPSTREAM_UNCHECKED = "unchecked"  # check off, or the upstream server could not be listened to


def _env(name: str) -> str:
    return (os.environ.get(name) or "").strip()


def base_url() -> str:
    return _env("NTFY_BASE_URL").rstrip("/")


def public_url() -> str:
    return (_env("NTFY_PUBLIC_URL") or base_url()).rstrip("/")


def is_enabled() -> bool:
    return bool(base_url())


def is_valid_topic(topic: str) -> bool:
    return bool(topic) and bool(TOPIC_PATTERN.match(topic))


def generate_topic() -> str:
    """An unguessable topic name.

    On a server without access control the topic name is the only secret standing between a stranger
    and the alerts, so it gets ~119 bits of randomness (ntfy's own advice: treat it like a password).
    """
    suffix = "".join(secrets.choice(_GENERATED_TOPIC_ALPHABET) for _ in range(20))
    return TOPIC_PREFIX + suffix


def subscribe_url(topic: str) -> str:
    return f"{public_url()}/{topic}"


def _timeout() -> float:
    try:
        return max(1.0, float(_env("NTFY_TIMEOUT_SECONDS") or 5))
    except ValueError:
        return 5.0


def _auth_header() -> Optional[str]:
    token = _env("NTFY_TOKEN")
    if token:
        return f"Bearer {token}"
    username = _env("NTFY_USERNAME")
    if username:
        raw = f"{username}:{_env('NTFY_PASSWORD')}".encode("utf-8")
        return "Basic " + base64.b64encode(raw).decode("ascii")
    return None


def upstream_url() -> str:
    value = _env("NTFY_UPSTREAM_URL")
    if value.lower() in _UPSTREAM_OFF:
        return ""
    return (value or "https://ntfy.sh").rstrip("/")


def _upstream_wait() -> float:
    try:
        return max(1.0, float(_env("NTFY_UPSTREAM_WAIT_SECONDS") or 15))
    except ValueError:
        return 15.0


def upstream_topic(topic: str) -> str:
    """The upstream topic the iOS app listens on: the SHA-256 of the topic's URL.

    The ntfy server hashes its own base-url, which is the public URL phones subscribe with
    (NTFY_PUBLIC_URL).
    """
    return hashlib.sha256(f"{public_url()}/{topic}".encode("utf-8")).hexdigest()


def _upstream_headers() -> dict:
    return {"User-Agent": "Ariel-relay"}


class _PollRequestWatch:
    """Listens on the upstream server for the poll requests of one topic.

    Opened before publishing: the upstream server does not keep poll requests, so only a subscriber
    that is already listening sees them.
    """

    def __init__(self, topic: str):
        self.topic = topic
        self._seen = set()
        self._changed = threading.Condition()
        self._response = None

    def open(self) -> bool:
        request = urllib.request.Request(
            f"{upstream_url()}/{upstream_topic(self.topic)}/json", headers=_upstream_headers()
        )
        try:
            self._response = urllib.request.urlopen(request, timeout=min(_timeout(), UPSTREAM_OPEN_TIMEOUT_SECONDS))
            # ntfy's first line is the "open" event: the subscription is live from then on.
            if json.loads(self._response.readline() or b"{}").get("event") != "open":
                raise ValueError("no open event")
            # The reads after it get the whole wait, as ntfy is silent until a poll request.
            try:
                self._response.fp.raw._sock.settimeout(_upstream_wait() + 5)
            except AttributeError:
                pass
        except Exception as error:
            LOGGER.info("Cannot listen on %s for poll requests (%s); not checking this one", upstream_url(), error)
            self.close()
            return False
        threading.Thread(target=self._read, daemon=True).start()
        return True

    def _read(self) -> None:
        try:
            for line in self._response:
                event = json.loads(line or b"{}")
                if event.get("event") == "poll_request" and event.get("poll_id"):
                    with self._changed:
                        self._seen.add(event["poll_id"])
                        self._changed.notify_all()
        except Exception:
            pass  # closed, or no traffic until the read timeout

    def wait_for(self, message_id: str, seconds: float) -> bool:
        with self._changed:
            return self._changed.wait_for(lambda: message_id in self._seen, timeout=seconds)

    def close(self) -> None:
        if self._response is not None:
            try:
                # _read holds the response's lock until its read returns, which closing would wait for:
                # up to the read timeout, as ntfy is silent. Shutting the socket down ends that read.
                self._response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
            except (AttributeError, OSError):
                pass
            try:
                self._response.close()
            except Exception:
                pass


def _forward_poll_request(topic: str, message_id: str) -> bool:
    """Sends the poll request the ntfy server should have: an empty POST to the hashed topic with
    X-Poll-ID, exactly what its upstream-base-url does."""
    headers = {"X-Poll-ID": message_id, **_upstream_headers()}
    token = _env("NTFY_UPSTREAM_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"{upstream_url()}/{upstream_topic(topic)}", data=b"", headers=headers, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=_timeout()) as response:
            return 200 <= response.status < 300
    except urllib.error.HTTPError as error:
        hint = (
            ": too many messages today from this IP; NTFY_UPSTREAM_TOKEN raises the limit"
            if error.code == 429 else ""
        )
        LOGGER.warning("%s refused the relay's poll request for topic %r (HTTP %d)%s", upstream_url(), topic, error.code, hint)
    except Exception as error:
        LOGGER.warning("The relay's poll request for topic %r to %s failed: %s", topic, upstream_url(), error)
    return False


def _confirm_upstream(watch: _PollRequestWatch, message_id: str) -> str:
    """Waits for the ntfy server's poll request, and sends it when it does not come."""
    started = time.monotonic()
    try:
        if watch.wait_for(message_id, _upstream_wait()):
            LOGGER.info(
                "ntfy poll request for topic %r reached %s after %.1fs",
                watch.topic, upstream_url(), time.monotonic() - started,
            )
            return UPSTREAM_CONFIRMED
        forwarded = _forward_poll_request(watch.topic, message_id)
        LOGGER.warning(
            "The ntfy server's poll request for topic %r (message %s) did not reach %s within %.0fs; %s. "
            "Check upstream-base-url and base-url on the ntfy server (README).",
            watch.topic, message_id, upstream_url(), _upstream_wait(),
            "the relay sent it instead" if forwarded else "the relay could not send it either",
        )
        return UPSTREAM_FORWARDED if forwarded else UPSTREAM_FAILED
    finally:
        watch.close()


class _Attempt(NamedTuple):
    delivered: bool
    watch: Optional[_PollRequestWatch] = None
    message_id: Optional[str] = None
    # The failure may be gone in a moment: ntfy unreachable, overloaded or restarting.
    retryable: bool = False


def publish(
    topic: str,
    title: str,
    message: str,
    tags: Iterable[str] = (),
    priority: int = PRIORITY_MAX,
    click: Optional[str] = None,
) -> bool:
    """Publishes one notification to one topic, retrying what may work a moment later. Never raises.

    The upstream check (module docstring) runs in the background.
    """
    for attempt in range(1, PUBLISH_ATTEMPTS + 1):
        result = _publish(topic, title, message, tags, priority, click)
        if result.delivered and result.watch is not None and result.message_id:
            threading.Thread(target=_confirm_upstream, args=(result.watch, result.message_id), daemon=True).start()
            return True
        if result.watch is not None:
            result.watch.close()
        if result.delivered:
            return True
        if not result.retryable or attempt == PUBLISH_ATTEMPTS:
            return False
        time.sleep(attempt)
    return False


def publish_checked(
    topic: str,
    title: str,
    message: str,
    tags: Iterable[str] = (),
    priority: int = PRIORITY_MAX,
    click: Optional[str] = None,
) -> Tuple[bool, str]:
    """One publish, waiting for the upstream check: (delivered, one of UPSTREAM_*)."""
    result = _publish(topic, title, message, tags, priority, click)
    if result.watch is not None and result.message_id:
        return result.delivered, _confirm_upstream(result.watch, result.message_id)
    if result.watch is not None:
        result.watch.close()
    return result.delivered, UPSTREAM_UNCHECKED


def _publish(
    topic: str,
    title: str,
    message: str,
    tags: Iterable[str],
    priority: int,
    click: Optional[str],
) -> _Attempt:
    """Uses ntfy's JSON publishing (POST to the server root) rather than X-Title style headers: HTTP
    headers cannot carry non-ASCII text cleanly, and alerts have emoji in them."""
    url = base_url()
    if not url or not is_valid_topic(topic):
        return _Attempt(False)

    body = {
        "topic": topic,
        "title": title,
        "message": message,
        "priority": priority,
    }
    tags = [tag for tag in tags if tag]
    if tags:
        body["tags"] = tags
    if click:
        body["click"] = click

    headers = {"Content-Type": "application/json"}
    auth = _auth_header()
    if auth:
        headers["Authorization"] = auth

    request = urllib.request.Request(
        url + "/",
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    watch = None
    if upstream_url():
        watch = _PollRequestWatch(topic)
        if not watch.open():
            watch = None
    try:
        with urllib.request.urlopen(request, timeout=_timeout()) as response:
            delivered = 200 <= response.status < 300
            message_id = None
            if delivered and watch is not None:
                try:
                    message_id = json.loads(response.read() or b"{}").get("id")
                except Exception:
                    pass
            return _Attempt(delivered, watch, message_id)
    except urllib.error.HTTPError as error:
        # 401/403 mean the publisher credentials or ACL are wrong: say so plainly, it is by far the
        # most common setup mistake.
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace")[:200]
        except Exception:
            pass
        if error.code in (401, 403):
            LOGGER.warning(
                "ntfy rejected publish to topic %r (HTTP %d): check NTFY_TOKEN / NTFY_USERNAME and that "
                "the user has write access to ariel_*. %s",
                topic, error.code, detail,
            )
        else:
            LOGGER.warning("ntfy publish to topic %r failed (HTTP %d): %s", topic, error.code, detail)
        return _Attempt(False, watch, retryable=error.code == 429 or error.code >= 500)
    except Exception as error:
        LOGGER.warning("ntfy publish to topic %r failed: %s", topic, error)
        return _Attempt(False, watch, retryable=True)
