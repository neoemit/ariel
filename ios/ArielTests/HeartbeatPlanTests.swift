import XCTest
@testable import Ariel

final class HeartbeatPlanTests: XCTestCase {
    private let plan = HeartbeatPlan(relayURL: URL(string: "https://relay.example.com")!, buddyId: "User_1", appVersion: "1.65")

    func testHeartbeatsCoverThreeDaysStartingOneIntervalFromNow() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let dates = plan.beginDates(from: now)
        XCTAssertEqual(dates.count, HeartbeatPlan.count)
        XCTAssertEqual(dates.first, now.addingTimeInterval(HeartbeatPlan.interval))
        XCTAssertEqual(dates.last, now.addingTimeInterval(3 * 24 * 60 * 60))
        XCTAssertEqual(dates, dates.sorted())
    }

    func testAFullPlanIsLeftAlone() {
        XCTAssertTrue(plan.isCovered(byPending: Array(repeating: plan.key, count: HeartbeatPlan.count)))
        XCTAssertTrue(plan.isCovered(byPending: Array(repeating: plan.key, count: HeartbeatPlan.refillBelow)))
    }

    func testARunningOutOrForceQuitPlanIsRefilled() {
        XCTAssertFalse(plan.isCovered(byPending: Array(repeating: plan.key, count: HeartbeatPlan.refillBelow - 1)))
        XCTAssertFalse(plan.isCovered(byPending: []))
    }

    func testHeartbeatsForAnotherRelayBuddyOrVersionAreReplaced() {
        let others = [
            HeartbeatPlan(relayURL: URL(string: "https://other.example.com")!, buddyId: "User_1", appVersion: "1.65"),
            HeartbeatPlan(relayURL: plan.relayURL, buddyId: "User_2", appVersion: "1.65"),
            HeartbeatPlan(relayURL: plan.relayURL, buddyId: "User_1", appVersion: "1.66"),
        ]
        for other in others {
            var pending: [String?] = Array(repeating: plan.key, count: HeartbeatPlan.count)
            pending[0] = other.key
            XCTAssertFalse(plan.isCovered(byPending: pending))
        }
    }

    /// The relay's window (`ARIEL_IOS_PRESENCE_WINDOW_SECONDS`, 1800) has to outlast a late heartbeat.
    func testTheRelayWindowAllowsForLateHeartbeats() {
        XCTAssertGreaterThanOrEqual(1800 / HeartbeatPlan.interval, 3)
    }
}
