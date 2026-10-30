#pragma once

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

enum class TurnRelayTransport { Udp, Tcp, Tls };

struct TurnRelayOptions {
    TurnRelayTransport transport{TurnRelayTransport::Udp};
    const char *provider{""};
    const char *region{""};
};

struct TurnRelay {
    std::string host, username, password;
    uint16_t port{3478};
    TurnRelayTransport transport{TurnRelayTransport::Udp};
    std::string provider, region;

    bool sameService(const TurnRelay &other) const {
        return host == other.host && port == other.port && transport == other.transport &&
               username == other.username && password == other.password;
    }
    bool operator==(const TurnRelay &) const = default;
};

inline constexpr size_t maxTurnRelayPoolSize = 16;
inline constexpr size_t simultaneousTurnRelays = 2;

inline void appendTurnRelay(std::vector<TurnRelay> &pool, TurnRelay relay, bool allowEmptyCredentials = false) {
    if (relay.host.empty() || !relay.port ||
        (!allowEmptyCredentials && (relay.username.empty() || relay.password.empty()))) return;
    if (std::none_of(pool.begin(), pool.end(), [&](const auto &other) {
            return relay.sameService(other);
        })) pool.push_back(std::move(relay));
}

// libjuice allocates at most two relays per agent. Keep the larger provider /
// region pool outside the agent and rotate only after an ICE attempt fails.
// Signaling retries must not burn through relay choices or reset their health.
class TurnRelayRotation {
    std::mutex mutex;
    std::vector<TurnRelay> previousPool;
    size_t cursor = 0;
    uint64_t generation = 0;
    bool failed = false;
    bool negotiationStarted = false;
public:
    std::vector<TurnRelay> select(const std::vector<TurnRelay> &pool, uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if (pool != previousPool) {
            previousPool = pool;
            cursor = 0;
        }
        generation = agentGeneration;
        failed = false;
        negotiationStarted = false;
        if (pool.empty()) return {};
        cursor %= pool.size();
        std::vector<TurnRelay> selected{pool[cursor]};
        if (pool.size() == 1) return selected;
        size_t next = (cursor + 1) % pool.size();
        int bestScore = -1;
        for (size_t offset = 1; offset < pool.size(); ++offset) {
            const size_t index = (cursor + offset) % pool.size();
            const auto &candidate = pool[index];
            const auto &first = selected.front();
            // Prefer another transport (UDP-blocking networks), then another
            // provider (outages / quotas), then another region.
            const int score = 4 * (candidate.transport != first.transport) +
                2 * (!candidate.provider.empty() && candidate.provider != first.provider) +
                (!candidate.region.empty() && candidate.region != first.region);
            if (score > bestScore) { bestScore = score; next = index; }
        }
        selected.push_back(pool[next]);
        return selected;
    }
    void beginAttempt(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if(agentGeneration==generation) negotiationStarted=true;
    }
    void connected(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if(agentGeneration==generation) negotiationStarted=false;
    }
    void failedAttempt(uint64_t agentGeneration) {
        std::lock_guard lock(mutex);
        if (agentGeneration != generation || failed || !negotiationStarted || previousPool.empty()) return;
        // Every entry becomes the first choice within one traversal of the
        // pool, regardless of which alternative won the diversity ranking.
        cursor = (cursor + 1) % previousPool.size();
        failed = true;
        negotiationStarted = false;
    }
};
