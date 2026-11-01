import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import Ariel

/// Records requests and answers them with canned responses.
private final class FakeTransport: HTTPTransport, @unchecked Sendable {
    var status = 200
    var response = Data("{}".utf8)
    var error: Error?
    private(set) var requests: [URLRequest] = []

    func send(_ request: URLRequest) async throws -> (status: Int, body: Data) {
        requests.append(request)
        if let error { throw error }
        return (status, response)
    }

    var lastPath: String? { requests.last?.url?.path }

    var lastBody: [String: Any] {
        guard let body = requests.last?.httpBody,
              let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else { return [:] }
        return json
    }
}

final class RelayClientTests: XCTestCase {
    private var transport: FakeTransport!
    private var client: RelayClient!

    override func setUp() {
        super.setUp()
        transport = FakeTransport()
        client = RelayClient(baseURL: URL(string: "https://relay.example.com/")!, appVersion: "1.64", transport: transport)
    }

    func testRegisterSendsTokenAndPlatform() async throws {
        try await client.registerDevice(buddyId: "User_1", token: "token")
        XCTAssertEqual(transport.requests.last?.url?.absoluteString, "https://relay.example.com/v1/register-device")
        XCTAssertEqual(transport.requests.last?.httpMethod, "POST")
        XCTAssertEqual(transport.lastBody["buddyId"] as? String, "User_1")
        XCTAssertEqual(transport.lastBody["token"] as? String, "token")
        XCTAssertEqual(transport.lastBody["platform"] as? String, "ios")
        XCTAssertEqual(transport.lastBody["appVersion"] as? String, "1.64")
    }

    func testHeartbeat() async throws {
        try await client.heartbeat(buddyId: "User_1")
        XCTAssertEqual(transport.lastPath, "/v1/heartbeat")
        XCTAssertEqual(transport.lastBody["buddyId"] as? String, "User_1")
    }

    func testHeartbeatUploadKeepsTheBodyApartForABackgroundSession() throws {
        let upload = try client.heartbeatUpload(buddyId: "User_1")
        XCTAssertEqual(upload.request.url?.absoluteString, "https://relay.example.com/v1/heartbeat")
        XCTAssertEqual(upload.request.httpMethod, "POST")
        XCTAssertEqual(upload.request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertNil(upload.request.httpBody, "A background upload task refuses a request with a body")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: upload.body) as? [String: Any])
        XCTAssertEqual(body["buddyId"] as? String, "User_1")
        XCTAssertEqual(body["platform"] as? String, "ios")
        XCTAssertEqual(body["appVersion"] as? String, "1.64")
        XCTAssertTrue(transport.requests.isEmpty, "Building it sends nothing")
    }

    func testPresence() async throws {
        transport.response = Data(#"{"status":"ok","onlineBuddyIds":["User_2"],"linkedCount":2}"#.utf8)
        let online = try await client.presence(buddyIds: ["User_2", "User_3", " ", "User_2"], staleAfterSeconds: 5)
        XCTAssertEqual(online, ["User_2"])
        XCTAssertEqual(transport.lastBody["buddyIds"] as? [String], ["User_2", "User_3"])
        XCTAssertEqual(transport.lastBody["staleAfterSeconds"] as? Int, 30)
    }

    func testPresenceOfNobodyAsksNothing() async throws {
        let online = try await client.presence(buddyIds: [])
        XCTAssertEqual(online, [])
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testPanic() async throws {
        try await client.sendPanic(senderId: "User_1", eventId: "e1", escalation: .medical, recipientIds: ["User_2"])
        XCTAssertEqual(transport.lastPath, "/v1/panic")
        XCTAssertEqual(transport.lastBody["escalationType"] as? String, "MEDICAL")
        XCTAssertEqual(transport.lastBody["recipientIds"] as? [String], ["User_2"])
    }

    func testPanicReturnsTheBuddiesNothingAlerted() async throws {
        transport.response = Data(#"{"status":"queued","deliveredTokens":0,"unreachedRecipientIds":["User_2"]}"#.utf8)
        let unreached = try await client.sendPanic(
            senderId: "User_1", eventId: "e1", escalation: .generic, recipientIds: ["User_2", "User_3"]
        )
        XCTAssertEqual(unreached, ["User_2"])
    }

    func testPanicToAnOlderRelayReachesEveryone() async throws {
        transport.response = Data(#"{"status":"sent","deliveredTokens":1}"#.utf8)
        let unreached = try await client.sendPanic(senderId: "User_1", eventId: "e1", escalation: .generic, recipientIds: ["User_2"])
        XCTAssertEqual(unreached, [])
    }

    func testAcknowledgment() async throws {
        try await client.sendAcknowledgment(senderId: "User_2", acknowledgerId: "User_1", eventId: "e1")
        XCTAssertEqual(transport.lastPath, "/v1/ack")
        XCTAssertEqual(transport.lastBody["senderId"] as? String, "User_2")
        XCTAssertEqual(transport.lastBody["acknowledgerId"] as? String, "User_1")
    }

    func testEvents() async throws {
        transport.response = Data("""
        {"status":"ok","cursor":7,"events":[
          {"id":6,"type":"panic","senderId":"User_2","eventId":"e1","escalationType":"ARMED","createdAt":1},
          {"id":7,"type":"ack","acknowledgerId":"User_3","eventId":"e0","createdAt":2},
          {"id":8,"type":"mystery"}
        ]}
        """.utf8)
        let (events, cursor) = try await client.events(buddyId: "User_1", afterId: 5)
        XCTAssertEqual(events, [
            .panic(senderId: "User_2", eventId: "e1", escalation: .armed),
            .ack(acknowledgerId: "User_3", eventId: "e0"),
        ])
        XCTAssertEqual(cursor, 7)
        XCTAssertEqual(transport.lastBody["afterId"] as? Int, 5)
    }

    func testHttpErrorsThrow() async {
        transport.status = 503
        do {
            try await client.heartbeat(buddyId: "User_1")
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RelayError, .http(503))
        }
    }

    func testUnexpectedBodiesThrow() async {
        transport.response = Data("not json".utf8)
        do {
            _ = try await client.presence(buddyIds: ["User_2"])
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RelayError, .invalidResponse)
        }
    }

    func testHealth() async throws {
        transport.response = Data(#"{"status":"ok","firebaseReady":true}"#.utf8)
        let health = try await client.health()
        XCTAssertTrue(health.reachable)
        XCTAssertTrue(health.pushReady)
        XCTAssertEqual(transport.requests.last?.httpMethod, "GET")
        XCTAssertEqual(transport.lastPath, "/health")
        XCTAssertNil(health.ntfyServerURL)
    }

    func testHealthSaysWhereNtfyIs() async throws {
        transport.response = Data(#"{"status":"ok","firebaseReady":false,"ntfyEnabled":true,"ntfyServerUrl":"https://ntfy.example.com"}"#.utf8)
        let health = try await client.health()
        XCTAssertFalse(health.pushReady)
        XCTAssertEqual(health.ntfyServerURL, "https://ntfy.example.com")
    }

    func testSubscribeNtfy() async throws {
        transport.response = Data(#"{"status":"ok","topic":"ariel_alice","serverUrl":"https://ntfy.example.com","subscribeUrl":"https://ntfy.example.com/ariel_alice"}"#.utf8)
        let subscription = try await client.subscribeNtfy(buddyId: "User_1", topic: "ariel_alice")
        XCTAssertEqual(subscription, NtfySubscription(topic: "ariel_alice", serverURL: "https://ntfy.example.com"))
        XCTAssertEqual(transport.lastPath, "/v1/ntfy/subscribe")
        XCTAssertEqual(transport.lastBody["buddyId"] as? String, "User_1")
        XCTAssertEqual(transport.lastBody["topic"] as? String, "ariel_alice")
    }

    func testSubscribeNtfyWithoutATopicAsksForARandomOne() async throws {
        transport.response = Data(#"{"status":"ok","topic":"ariel_x","serverUrl":"https://ntfy.example.com"}"#.utf8)
        let subscription = try await client.subscribeNtfy(buddyId: "User_1", topic: nil)
        XCTAssertEqual(subscription.topic, "ariel_x")
        XCTAssertNil(transport.lastBody["topic"])
    }

    func testUnsubscribeNtfy() async throws {
        try await client.unsubscribeNtfy(buddyId: "User_1", topic: "ariel_alice")
        XCTAssertEqual(transport.lastPath, "/v1/ntfy/unsubscribe")
        XCTAssertEqual(transport.lastBody["topic"] as? String, "ariel_alice")
    }

    func testTestNtfyWaitsLongerThanOtherCalls() async throws {
        transport.response = Data(#"{"status":"ok","delivered":true,"upstream":"forwarded"}"#.utf8)
        let result = try await client.testNtfy(buddyId: "User_1", topic: "ariel_alice")
        XCTAssertEqual(result, NtfyTestResult(delivered: true, upstream: "forwarded"))
        XCTAssertEqual(transport.lastPath, "/v1/ntfy/test")
        XCTAssertEqual(transport.requests.last?.timeoutInterval, RelayClient.ntfyTestTimeout)
    }

    func testNtfyRefusalsThrowTheirStatus() async {
        transport.status = 409
        do {
            _ = try await client.subscribeNtfy(buddyId: "User_1", topic: nil)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? RelayError, .http(409))
        }
    }
}
