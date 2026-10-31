import XCTest
@testable import Ariel

final class EscalationTests: XCTestCase {
    func testWireValuesMatchAndroidAndTheRelay() {
        XCTAssertEqual(Escalation.allCases.map(\.rawValue), ["GENERIC", "MEDICAL", "ARMED"])
    }

    func testUnknownValuesFallBackToGeneric() {
        XCTAssertEqual(Escalation(wireValue: "medical"), .medical)
        XCTAssertEqual(Escalation(wireValue: " ARMED "), .armed)
        XCTAssertEqual(Escalation(wireValue: "FIRE"), .generic)
        XCTAssertEqual(Escalation(wireValue: nil), .generic)
    }

    func testAlertTextMatchesAndroid() {
        XCTAssertEqual(Escalation.medical.alertTitle, "ARIEL PANIC ALERT 🚑")
        XCTAssertEqual(Escalation.armed.alertBody(for: "Sam"), "Sam is in danger and requires armed response.")
        XCTAssertEqual(Escalation.generic.alertBody(for: "Sam"), "Sam requires urgent assistance.")
    }
}

final class RelayEventTests: XCTestCase {
    func testParsesPanicPushData() {
        let event = RelayEvent(userInfo: [
            "type": "panic", "senderId": "User_1111", "eventId": "e1", "escalationType": "MEDICAL",
            "aps": ["alert": ["title": "x"]],
        ])
        XCTAssertEqual(event, .panic(senderId: "User_1111", eventId: "e1", escalation: .medical))
    }

    func testParsesAck() {
        let event = RelayEvent(userInfo: ["type": "ACK", "acknowledgerId": "User_2222", "eventId": "e1"])
        XCTAssertEqual(event, .ack(acknowledgerId: "User_2222", eventId: "e1"))
    }

    func testRejectsIncompleteOrUnknownPayloads() {
        XCTAssertNil(RelayEvent(userInfo: ["type": "panic", "eventId": "e1"]))
        XCTAssertNil(RelayEvent(userInfo: ["type": "panic", "senderId": " ", "eventId": "e1"]))
        XCTAssertNil(RelayEvent(userInfo: ["type": "ack", "acknowledgerId": "User_2222"]))
        XCTAssertNil(RelayEvent(userInfo: ["type": "chat", "senderId": "a", "eventId": "e1"]))
        XCTAssertNil(RelayEvent(userInfo: [:]))
    }

    func testUserInfoRoundTrips() {
        let events: [RelayEvent] = [
            .panic(senderId: "User_1111", eventId: "e1", escalation: .armed),
            .ack(acknowledgerId: "User_2222", eventId: "e1"),
        ]
        for event in events {
            XCTAssertEqual(RelayEvent(userInfo: event.userInfo), event)
        }
    }

    func testEachAcknowledgerOfAPanicIsKeptApart() {
        let panic = RelayEvent.panic(senderId: "a", eventId: "e1", escalation: .generic)
        let first = RelayEvent.ack(acknowledgerId: "b", eventId: "e1")
        let second = RelayEvent.ack(acknowledgerId: "c", eventId: "e1")
        XCTAssertEqual(Set([panic, first, second].map(\.deduplicationKey)).count, 3)
    }
}

final class EventLogTests: XCTestCase {
    func testInsertReportsDuplicates() {
        var log = EventLog()
        XCTAssertTrue(log.insert("a"))
        XCTAssertFalse(log.insert("a"))
        XCTAssertTrue(log.contains("a"))
    }

    func testKeepsTheNewestEntries() {
        var log = EventLog()
        for index in 0..<(EventLog.capacity + 5) {
            log.insert("e\(index)")
        }
        XCTAssertEqual(log.keys.count, EventLog.capacity)
        XCTAssertFalse(log.contains("e0"))
        XCTAssertTrue(log.contains("e\(EventLog.capacity + 4)"))
    }
}

final class BuddyIDTests: XCTestCase {
    func testGeneratedIdsUseTheAndroidFormat() {
        let id = BuddyID.generate()
        XCTAssertTrue(id.hasPrefix("User_"))
        XCTAssertEqual(Int(id.dropFirst(5)).map { (1000...9999).contains($0) }, true)
    }

    func testSanitize() {
        XCTAssertEqual(BuddyID.sanitize("  User_1234\n"), "User_1234")
        XCTAssertNil(BuddyID.sanitize("   "))
        XCTAssertNil(BuddyID.sanitize("a\nb"))
        XCTAssertNil(BuddyID.sanitize(String(repeating: "x", count: BuddyID.maxLength + 1)))
    }

    func testSanitizeFriendsDropsSelfBlanksAndDuplicates() {
        XCTAssertEqual(
            BuddyID.sanitizeFriends(["User_1", " ", "user_me", "User_2", "User_1 "], myId: "User_Me"),
            ["User_1", "User_2"]
        )
    }
}

final class RelayURLTests: XCTestCase {
    func testNormalize() {
        XCTAssertEqual(RelayURL.normalize(" https://relay.example.com/ "), "https://relay.example.com")
        XCTAssertEqual(RelayURL.normalize("http://192.168.1.20:8001"), "http://192.168.1.20:8001")
        XCTAssertEqual(RelayURL.normalize(""), "")
        XCTAssertNil(RelayURL.normalize("relay.example.com"))
        XCTAssertNil(RelayURL.normalize("ftp://relay.example.com"))
        XCTAssertNil(RelayURL.normalize("https://"))
    }

    func testResolveFallsBackToTheDefault() {
        XCTAssertEqual(RelayURL.resolve(nil), RelayURL.default)
        XCTAssertEqual(RelayURL.resolve(""), RelayURL.default)
        XCTAssertEqual(RelayURL.resolve("nonsense"), RelayURL.default)
        XCTAssertEqual(RelayURL.resolve("http://10.0.0.2:8001/").absoluteString, "http://10.0.0.2:8001")
    }
}

final class AppConfigurationTests: XCTestCase {
    func testPushNeedsBothTheBuildSettingAndFirebase() {
        var configuration = AppConfiguration(pushRequested: true, hasFirebaseConfig: true, version: "1.64", build: "64")
        XCTAssertEqual(configuration.deliveryMode, .push)
        configuration.hasFirebaseConfig = false
        XCTAssertEqual(configuration.deliveryMode, .inApp)
        configuration = AppConfiguration(pushRequested: false, hasFirebaseConfig: true, version: "1.64", build: "64")
        XCTAssertEqual(configuration.deliveryMode, .inApp)
        XCTAssertEqual(configuration.versionDescription, "1.64 (64)")
    }

    func testInfoPlistFlags() {
        XCTAssertTrue(AppConfiguration.isYes("YES"))
        XCTAssertTrue(AppConfiguration.isYes(true))
        XCTAssertFalse(AppConfiguration.isYes("NO"))
        XCTAssertFalse(AppConfiguration.isYes(""))
        XCTAssertFalse(AppConfiguration.isYes(nil))
    }
}

final class PreferencesTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "ariel-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testMarkHandledOnlyOnce() {
        let prefs = Preferences(defaults: defaults)
        let event = RelayEvent.panic(senderId: "a", eventId: "e1", escalation: .generic)
        XCTAssertTrue(prefs.markHandled(event))
        XCTAssertFalse(Preferences(defaults: defaults).markHandled(event))
    }

    func testClearPoolKeepsIdentityAndSettings() {
        let prefs = Preferences(defaults: defaults)
        prefs.myId = "User_1"
        prefs.friends = ["User_2"]
        prefs.nicknames = ["User_2": "Sam"]
        prefs.discreetMode = true
        prefs.lastOnlineBuddyIds = ["User_2"]
        prefs.clearPool()
        XCTAssertEqual(prefs.myId, "User_1")
        XCTAssertEqual(prefs.friends, [])
        XCTAssertEqual(prefs.nicknames, [:])
        XCTAssertEqual(prefs.lastOnlineBuddyIds, [])
        XCTAssertTrue(prefs.discreetMode)
    }
}
