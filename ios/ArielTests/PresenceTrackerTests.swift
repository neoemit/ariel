import XCTest
@testable import Ariel

final class PresenceTrackerTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_000_000)
    private let friends: Set<String> = ["User_1", "User_2"]

    private func at(_ seconds: TimeInterval) -> Date { start.addingTimeInterval(seconds) }

    func testOnlineBuddiesAreShownStraightAway() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: ["User_1", "Stranger"], friends: friends, now: at(1))
        XCTAssertEqual(tracker.onlineIds, ["User_1"])
        XCTAssertFalse(tracker.isChecking)
        XCTAssertEqual(tracker.nextPollDelay, PresenceTracker.pollInterval)
    }

    func testKeepsCheckingDuringTheDiscoveryWindow() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: [], friends: friends, now: at(10))
        tracker.update(relayOnlineIds: nil, friends: friends, now: at(50))
        XCTAssertTrue(tracker.isChecking)
        XCTAssertEqual(tracker.nextPollDelay, PresenceTracker.checkingRetryInterval)
    }

    func testTwoEmptyAnswersAfterTheWindowMeanOffline() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: [], friends: friends, now: at(61))
        XCTAssertTrue(tracker.isChecking)
        XCTAssertEqual(tracker.nextPollDelay, PresenceTracker.zeroConfirmationDelay)
        tracker.update(relayOnlineIds: [], friends: friends, now: at(64))
        XCTAssertFalse(tracker.isChecking)
        XCTAssertEqual(tracker.onlineIds, [])
    }

    func testBuddiesThatWereOnlineAreHeldDuringTheGrace() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: ["User_1"], friends: friends, now: at(100))
        tracker.update(relayOnlineIds: [], friends: friends, now: at(200))
        XCTAssertEqual(tracker.onlineIds, ["User_1"])
        XCTAssertTrue(tracker.isChecking)

        tracker.update(relayOnlineIds: [], friends: friends, now: at(230))
        XCTAssertEqual(tracker.onlineIds, [])
        tracker.update(relayOnlineIds: [], friends: friends, now: at(233))
        XCTAssertFalse(tracker.isChecking)
    }

    func testNoFriendsIsSettledAtOnce() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: ["User_1"], friends: [], now: at(1))
        XCTAssertEqual(tracker.onlineIds, [])
        XCTAssertFalse(tracker.isChecking)
    }

    func testRestartingDiscoveryChecksAgain() {
        var tracker = PresenceTracker(now: start)
        tracker.update(relayOnlineIds: [], friends: friends, now: at(61))
        tracker.update(relayOnlineIds: [], friends: friends, now: at(64))
        tracker.restartDiscovery(now: at(100))
        XCTAssertTrue(tracker.isChecking)
        tracker.update(relayOnlineIds: [], friends: friends, now: at(110))
        XCTAssertTrue(tracker.isChecking)
    }

    func testStatus() {
        XCTAssertEqual(PresenceStatus(confirmedOnlineCount: 1, recentlySeenCount: 0, isChecking: true), .online)
        XCTAssertEqual(PresenceStatus(confirmedOnlineCount: 0, recentlySeenCount: 2, isChecking: true), .recentlySeenChecking)
        XCTAssertEqual(PresenceStatus(confirmedOnlineCount: 0, recentlySeenCount: 0, isChecking: true), .checking)
        XCTAssertEqual(PresenceStatus(confirmedOnlineCount: 0, recentlySeenCount: 2, isChecking: false), .offline)
    }

    func testRecentlySeen() {
        let seen = PresenceTracker.recentlySeen(
            cachedIds: ["User_1", " User_2 ", "Removed"], trustedIds: friends, seenAt: at(0), now: at(299)
        )
        XCTAssertEqual(seen, ["User_1", "User_2"])
        XCTAssertEqual(PresenceTracker.recentlySeen(cachedIds: ["User_1"], trustedIds: friends, seenAt: at(0), now: at(301)), [])
        XCTAssertEqual(PresenceTracker.recentlySeen(cachedIds: ["User_1"], trustedIds: friends, seenAt: at(10), now: at(0)), [])
        XCTAssertEqual(PresenceTracker.recentlySeen(cachedIds: ["User_1"], trustedIds: friends, seenAt: nil, now: at(0)), [])
    }
}
