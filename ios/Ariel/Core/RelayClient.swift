import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Sends a request and returns the HTTP status and body. Swapped out in tests.
protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (status: Int, body: Data)
}

struct URLSessionTransport: HTTPTransport {
    var session: URLSession = .shared

    func send(_ request: URLRequest) async throws -> (status: Int, body: Data) {
        let (data, response) = try await session.data(for: request)
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
    }
}

enum RelayError: Error, Equatable {
    /// The relay answered with this status.
    case http(Int)
    /// The relay answered 2xx with a body that is not what the API returns.
    case invalidResponse
}

/// The relay backend's API (backend/app/main.py), the iOS counterpart of Android's RelayBackendClient.
/// Every call throws when the relay cannot be reached or does not answer 2xx.
struct RelayClient: Sendable {
    static let platform = "ios"
    static let timeout: TimeInterval = 7

    let baseURL: URL
    var appVersion: String?
    var transport: HTTPTransport = URLSessionTransport()

    /// Registers this device's FCM token under its buddy ID; also counts as a heartbeat.
    func registerDevice(buddyId: String, token: String) async throws {
        try await post("/v1/register-device", RegisterBody(
            buddyId: buddyId, token: token, appVersion: appVersion, platform: Self.platform
        ))
    }

    /// Marks this buddy online, for builds without push.
    func heartbeat(buddyId: String) async throws {
        try await post("/v1/heartbeat", HeartbeatBody(buddyId: buddyId, appVersion: appVersion, platform: Self.platform))
    }

    /// Which of these buddies were seen within `staleAfterSeconds`.
    func presence(buddyIds: [String], staleAfterSeconds: Int = 180) async throws -> Set<String> {
        let ids = Array(Set(buddyIds.map(\.trimmed).filter { !$0.isEmpty })).sorted()
        guard !ids.isEmpty else { return [] }
        let body = PresenceBody(buddyIds: ids, staleAfterSeconds: min(max(staleAfterSeconds, 30), 3600))
        let response: PresenceResponse = try await postDecoding("/v1/presence", body)
        return Set(response.onlineBuddyIds.map(\.trimmed).filter { !$0.isEmpty })
    }

    func sendPanic(senderId: String, eventId: String, escalation: Escalation, recipientIds: [String]) async throws {
        try await post("/v1/panic", PanicBody(
            senderId: senderId, eventId: eventId, escalationType: escalation.rawValue, recipientIds: recipientIds
        ))
    }

    /// Tells `senderId`, who raised the panic `eventId`, that this buddy is coming.
    func sendAcknowledgment(senderId: String, acknowledgerId: String, eventId: String) async throws {
        try await post("/v1/ack", AckBody(senderId: senderId, acknowledgerId: acknowledgerId, eventId: eventId))
    }

    /// Panics and acknowledgments for `buddyId` newer than `afterId`, and the cursor to pass next time.
    func events(buddyId: String, afterId: Int, maxAgeSeconds: Int = 300) async throws -> (events: [RelayEvent], cursor: Int) {
        let response: EventsResponse = try await postDecoding("/v1/events", EventsBody(
            buddyId: buddyId, afterId: max(afterId, 0), maxAgeSeconds: maxAgeSeconds
        ))
        let events = response.events.compactMap {
            RelayEvent(
                type: $0.type,
                senderId: $0.senderId,
                eventId: $0.eventId,
                escalationType: $0.escalationType,
                acknowledgerId: $0.acknowledgerId
            )
        }
        return (events, response.cursor)
    }

    /// Whether the relay answers, and whether it can send pushes.
    func health() async throws -> (reachable: Bool, pushReady: Bool) {
        var request = URLRequest(url: url("/health"), timeoutInterval: Self.timeout)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (status, body) = try await transport.send(request)
        guard (200..<300).contains(status) else { throw RelayError.http(status) }
        let health = try? JSONDecoder().decode(HealthResponse.self, from: body)
        return (true, health?.firebaseReady ?? false)
    }

    // MARK: - Plumbing

    private func url(_ path: String) -> URL {
        URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path)!
    }

    @discardableResult
    private func post<Body: Encodable>(_ path: String, _ body: Body) async throws -> Data {
        var request = URLRequest(url: url(path), timeoutInterval: Self.timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(body)
        let (status, data) = try await transport.send(request)
        guard (200..<300).contains(status) else { throw RelayError.http(status) }
        return data
    }

    private func postDecoding<Body: Encodable, Response: Decodable>(_ path: String, _ body: Body) async throws -> Response {
        let data = try await post(path, body)
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw RelayError.invalidResponse
        }
    }
}

// MARK: - Wire format

private struct RegisterBody: Encodable {
    let buddyId: String
    let token: String
    let appVersion: String?
    let platform: String
}

private struct HeartbeatBody: Encodable {
    let buddyId: String
    let appVersion: String?
    let platform: String
}

private struct PresenceBody: Encodable {
    let buddyIds: [String]
    let staleAfterSeconds: Int
}

private struct PresenceResponse: Decodable {
    let onlineBuddyIds: [String]
}

private struct PanicBody: Encodable {
    let senderId: String
    let eventId: String
    let escalationType: String
    let recipientIds: [String]
}

private struct AckBody: Encodable {
    let senderId: String
    let acknowledgerId: String
    let eventId: String
}

private struct EventsBody: Encodable {
    let buddyId: String
    let afterId: Int
    let maxAgeSeconds: Int
}

private struct EventsResponse: Decodable {
    struct Event: Decodable {
        let type: String?
        let senderId: String?
        let eventId: String?
        let escalationType: String?
        let acknowledgerId: String?
    }

    let events: [Event]
    let cursor: Int
}

private struct HealthResponse: Decodable {
    let firebaseReady: Bool?
}
