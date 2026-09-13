// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.mcp.guard;
import com.compagnonsdudev.kafkasqlexplorer.mcp.McpProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
/** Per-caller token bucket with bounded identity state. */
public class McpRateLimiter {
 static final long MAX_IDENTITIES=10_000L; static final Duration IDLE_EXPIRY=Duration.ofMinutes(15);
 private static final class Bucket { private double tokens; private long lastRefillNanos; Bucket(double t){tokens=t;lastRefillNanos=System.nanoTime();} synchronized long tryConsume(double c,double r){long n=System.nanoTime();tokens=Math.min(c,tokens+(n-lastRefillNanos)/1_000_000_000.0*r);lastRefillNanos=n;if(tokens>=1){tokens-=1;return 0;}return (long)Math.ceil((1-tokens)/r*1000);}}
 private final McpProperties properties;
 private final Cache<String,Bucket> buckets=Caffeine.newBuilder().maximumSize(MAX_IDENTITIES).expireAfterAccess(IDLE_EXPIRY).build();
 public McpRateLimiter(McpProperties p){properties=p;}
 public void check(String identity){int pm=properties.getRateLimit().getCallsPerMinute();if(pm<=0)return;double r=pm/60.0,c=Math.max(1.0,properties.getRateLimit().getBurst());String k=identity==null?"unknown":identity;long w=buckets.get(k,x->new Bucket(c)).tryConsume(c,r);if(w>0)throw new McpToolException(McpErrorCode.RATE_LIMITED,McpGuard.RATE_LIMIT,("%s has used its allowance of %d calls per minute (burst %d). Wait about %d ms before the next call — retrying immediately is what this limit exists to stop. The limit is per server instance (explorer.mcp.rate-limit.calls-per-minute).").formatted(k,pm,(int)c,w));}
 public void reset(String identity){buckets.invalidate(identity);} int identityCount(){return (int)buckets.estimatedSize();}
}
