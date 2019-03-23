/*
 * Copyright © 2018 Mercateo AG (http://www.mercateo.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.factcast.store.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.factcast.core.Fact;
import org.factcast.core.FactCast;
import org.factcast.core.MarkFact;
import org.factcast.core.lock.Attempt;
import org.factcast.core.lock.AttemptAbortedException;
import org.factcast.core.lock.ExceptionAfterPublish;
import org.factcast.core.lock.opt.WithOptimisticLock.OptimisticRetriesExceededException;
import org.factcast.core.spec.FactSpec;
import org.factcast.core.store.FactStore;
import org.factcast.core.subscription.Subscription;
import org.factcast.core.subscription.SubscriptionRequest;
import org.factcast.core.subscription.SubscriptionRequestTO;
import org.factcast.core.subscription.observer.FactObserver;
import org.factcast.core.subscription.observer.IdObserver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.annotation.DirtiesContext;

import lombok.Getter;
import lombok.SneakyThrows;

public abstract class AbstractFactStoreTest {

    static final FactSpec ANY = FactSpec.ns("default");

    protected FactCast uut;

    protected FactStore store;

    @BeforeEach
    void setUp() {
        store = spy(createStoreToTest());
        uut = spy(FactCast.from(store));
    }

    protected abstract FactStore createStoreToTest();

    @Nested

    class BasicContract {
        @DirtiesContext
        @Test
        protected void testEmptyStore() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                Subscription s = uut.subscribeToFacts(SubscriptionRequest.catchup(ANY)
                        .fromScratch(),
                        observer);
                s.awaitComplete();
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, never()).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testUniquenessConstraint() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                Assertions.assertThrows(IllegalArgumentException.class, () -> {
                    final UUID id = UUID.randomUUID();
                    uut.publish(Fact.of("{\"id\":\"" + id
                            + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                    uut.publish(Fact.of("{\"id\":\"" + id
                            + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                    fail();
                });
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreFollowNonMatching() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.subscribeToFacts(SubscriptionRequest.follow(ANY).fromScratch(), observer)
                        .awaitCatchup();
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, never()).onNext(any());
                uut.publishWithMark(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"other\"}", "{}"));
                uut.publishWithMark(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"other\"}", "{}"));
                observer.await(2);
                // the mark facts only
                verify(observer, times(2)).onNext(any());
                assertEquals(MarkFact.MARK_TYPE, observer.values.get(0).type());
                assertEquals(MarkFact.MARK_TYPE, observer.values.get(1).type());
            });
        }

        private TestFactObserver testObserver() {
            return spy(new TestFactObserver());
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreFollowMatching() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.subscribeToFacts(SubscriptionRequest.follow(ANY).fromScratch(), observer)
                        .awaitCatchup();
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, never()).onNext(any());
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                observer.await(1);
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreEphemeral() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                TestFactObserver observer = testObserver();
                uut.subscribeToFacts(SubscriptionRequest.follow(ANY).fromNowOn(), observer)
                        .awaitCatchup();
                // nothing recieved
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, never()).onNext(any());
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                observer.await(1);
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreEphemeralWithCancel() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                Subscription subscription = uut.subscribeToFacts(SubscriptionRequest.follow(ANY)
                        .fromNowOn(), observer)
                        .awaitCatchup();
                // nothing recieved
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, never()).onNext(any());
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                observer.await(1);
                verify(observer, times(1)).onNext(any());
                subscription.close();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                Thread.sleep(100);
                // additional event not received
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreFollowWithCancel() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                Subscription subscription = uut.subscribeToFacts(SubscriptionRequest.follow(ANY)
                        .fromScratch(), observer)
                        .awaitCatchup();
                // nothing recieved
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer, times(3)).onNext(any());
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                observer.await(4);
                subscription.close();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                Thread.sleep(100);
                // additional event not received
                verify(observer, times(4)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreCatchupMatching() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.subscribeToFacts(SubscriptionRequest.catchup(ANY).fromScratch(), observer)
                        .awaitComplete();
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verify(observer, never()).onError(any());
                verify(observer).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreFollowMatchingDelayed() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                uut.subscribeToFacts(SubscriptionRequest.follow(ANY).fromScratch(), observer)
                        .awaitCatchup();
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer).onNext(any());
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                observer.await(2);
            });
        }

        @DirtiesContext
        @Test
        protected void testEmptyStoreFollowNonMatchingDelayed() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                TestFactObserver observer = testObserver();
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"t1\"}", "{}"));
                uut.subscribeToFacts(SubscriptionRequest.follow(ANY).fromScratch(), observer)
                        .awaitCatchup();
                verify(observer).onCatchup();
                verify(observer, never()).onComplete();
                verify(observer, never()).onError(any());
                verify(observer).onNext(any());
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"other\",\"type\":\"t1\"}", "{}"));
                observer.await(1);
            });
        }

        @DirtiesContext
        @Test
        protected void testFetchById() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                UUID id = UUID.randomUUID();
                uut.publish(
                        Fact.of("{\"id\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                Optional<Fact> f = uut.fetchById(id);
                assertFalse(f.isPresent());
                uut.publish(Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\"}",
                        "{}"));
                f = uut.fetchById(id);
                assertTrue(f.isPresent());
                assertEquals(id, f.map(Fact::id).orElse(null));
            });
        }

        @DirtiesContext
        @Test
        protected void testAnySubscriptionsMatchesMark() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                UUID mark = uut.publishWithMark(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\""
                        + UUID.randomUUID() + "\",\"type\":\"noone_knows\"}", "{}"));
                ArgumentCaptor<Fact> af = ArgumentCaptor.forClass(Fact.class);
                doNothing().when(observer).onNext(af.capture());
                uut.subscribeToFacts(SubscriptionRequest.catchup(ANY).fromScratch(), observer)
                        .awaitComplete();
                verify(observer).onNext(any());
                assertEquals(mark, af.getValue().id());
                verify(observer).onComplete();
                verify(observer).onCatchup();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testRequiredMetaAttribute() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\"}",
                        "{}"));
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"meta\":{\"foo\":\"bar\"}}",
                        "{}"));
                FactSpec REQ_FOO_BAR = FactSpec.ns("default").meta("foo", "bar");
                uut.subscribeToFacts(SubscriptionRequest.catchup(REQ_FOO_BAR).fromScratch(),
                        observer)
                        .awaitComplete();
                verify(observer).onNext(any());
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testScriptedWithPayloadFiltering() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(Fact.of(
                        "{\"id\":\"" + UUID.randomUUID()
                                + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"hit\":\"me\"}",
                        "{}"));
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"meta\":{\"foo\":\"bar\"}}",
                        "{}"));
                FactSpec SCRIPTED = FactSpec.ns("default")
                        .jsFilterScript(
                                "function (h,e){ return (h.hit=='me')}");
                uut.subscribeToFacts(SubscriptionRequest.catchup(SCRIPTED).fromScratch(), observer)
                        .awaitComplete();
                verify(observer).onNext(any());
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testScriptedWithHeaderFiltering() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(Fact.of(
                        "{\"id\":\"" + UUID.randomUUID()
                                + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"hit\":\"me\"}",
                        "{}"));
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"meta\":{\"foo\":\"bar\"}}",
                        "{}"));
                FactSpec SCRIPTED = FactSpec.ns("default")
                        .jsFilterScript(
                                "function (h){ return (h.hit=='me')}");
                uut.subscribeToFacts(SubscriptionRequest.catchup(SCRIPTED).fromScratch(), observer)
                        .awaitComplete();
                verify(observer).onNext(any());
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testScriptedFilteringMatchAll() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(Fact.of(
                        "{\"id\":\"" + UUID.randomUUID()
                                + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"hit\":\"me\"}",
                        "{}"));
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"meta\":{\"foo\":\"bar\"}}",
                        "{}"));
                FactSpec SCRIPTED = FactSpec.ns("default")
                        .jsFilterScript(
                                "function (h){ return true }");
                uut.subscribeToFacts(SubscriptionRequest.catchup(SCRIPTED).fromScratch(), observer)
                        .awaitComplete();
                verify(observer, times(2)).onNext(any());
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testScriptedFilteringMatchNone() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                FactObserver observer = mock(FactObserver.class);
                uut.publish(Fact.of(
                        "{\"id\":\"" + UUID.randomUUID()
                                + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"hit\":\"me\"}",
                        "{}"));
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID()
                        + "\",\"ns\":\"default\",\"type\":\"noone_knows\",\"meta\":{\"foo\":\"bar\"}}",
                        "{}"));
                FactSpec SCRIPTED = FactSpec.ns("default")
                        .jsFilterScript(
                                "function (h){ return false }");
                uut.subscribeToFacts(SubscriptionRequest.catchup(SCRIPTED).fromScratch(), observer)
                        .awaitComplete();
                verify(observer).onCatchup();
                verify(observer).onComplete();
                verifyNoMoreInteractions(observer);
            });
        }

        @DirtiesContext
        @Test
        protected void testIncludeMarks() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                uut.publishWithMark(Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                FactObserver observer = mock(FactObserver.class);
                uut.subscribeToFacts(SubscriptionRequest.catchup(FactSpec.ns("default"))
                        .fromScratch(),
                        observer)
                        .awaitComplete();
                verify(observer, times(2)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testSkipMarks() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                uut.publishWithMark(Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\"}", "{}"));
                FactObserver observer = mock(FactObserver.class);
                uut.subscribeToFacts(SubscriptionRequest.catchup(FactSpec.ns("default"))
                        .skipMarks()
                        .fromScratch(),
                        observer).awaitComplete();
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testMatchBySingleAggId() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                final UUID aggId1 = UUID.randomUUID();
                uut.publishWithMark(Fact.of(
                        "{\"id\":\"" + id
                                + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                                + aggId1 + "\"]}",
                        "{}"));
                FactObserver observer = mock(FactObserver.class);
                uut.subscribeToFacts(
                        SubscriptionRequest.catchup(FactSpec.ns("default").aggId(aggId1))
                                .skipMarks()
                                .fromScratch(),
                        observer).awaitComplete();
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testMatchByOneOfAggId() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                final UUID aggId1 = UUID.randomUUID();
                final UUID aggId2 = UUID.randomUUID();
                uut.publishWithMark(Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                        + aggId1 + "\",\"" + aggId2 + "\"]}", "{}"));
                FactObserver observer = mock(FactObserver.class);
                uut.subscribeToFacts(
                        SubscriptionRequest.catchup(FactSpec.ns("default").aggId(aggId1))
                                .skipMarks()
                                .fromScratch(),
                        observer).awaitComplete();
                verify(observer, times(1)).onNext(any());
                observer = mock(FactObserver.class);
                uut.subscribeToFacts(
                        SubscriptionRequest.catchup(FactSpec.ns("default").aggId(aggId2))
                                .skipMarks()
                                .fromScratch(),
                        observer).awaitComplete();
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testMatchBySecondAggId() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                final UUID aggId1 = UUID.randomUUID();
                final UUID aggId2 = UUID.randomUUID();
                uut.publishWithMark(Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                        + aggId1 + "\",\"" + aggId2 + "\"]}", "{}"));
                FactObserver observer = mock(FactObserver.class);
                uut.subscribeToFacts(
                        SubscriptionRequest.catchup(FactSpec.ns("default").aggId(aggId2))
                                .skipMarks()
                                .fromScratch(),
                        observer).awaitComplete();
                verify(observer, times(1)).onNext(any());
            });
        }

        @DirtiesContext
        @Test
        protected void testDelayed() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                TestFactObserver obs = new TestFactObserver();
                try (Subscription s = uut.subscribeToFacts(
                        SubscriptionRequest.follow(500, FactSpec.ns("default").aggId(id))
                                .skipMarks()
                                .fromScratch(), obs)) {
                    uut.publishWithMark(Fact.of(
                            "{\"id\":\"" + id
                                    + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                                    + id
                                    + "\"]}",
                            "{}"));
                    // will take some time on pgstore
                    obs.await(1);
                }
            });
        }

        @DirtiesContext
        @Test
        protected void testSerialOf() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                final UUID id = UUID.randomUUID();
                assertFalse(uut.serialOf(id).isPresent());
                UUID mark1 = uut.publishWithMark(Fact.of(
                        "{\"id\":\"" + id
                                + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                                + id + "\"]}",
                        "{}"));
                assertTrue(uut.serialOf(mark1).isPresent());
                assertTrue(uut.serialOf(id).isPresent());
                long serMark = uut.serialOf(mark1).getAsLong();
                long serFact = uut.serialOf(id).getAsLong();
                assertTrue(serFact < serMark);
            });
        }

        @DirtiesContext
        @Test
        protected void testSerialHeader() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                UUID id = UUID.randomUUID();
                uut.publish(Fact.of(
                        "{\"id\":\"" + id
                                + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\""
                                + id + "\"]}",
                        "{}"));
                UUID id2 = UUID.randomUUID();
                uut.publish(Fact.of("{\"id\":\"" + id2
                        + "\",\"type\":\"someType\",\"meta\":{\"foo\":\"bar\"},\"ns\":\"default\",\"aggIds\":[\""
                        + id2
                        + "\"]}", "{}"));
                OptionalLong serialOf = uut.serialOf(id);
                assertTrue(serialOf.isPresent());
                Fact f = uut.fetchById(id).get();
                Fact fact2 = uut.fetchById(id2).get();
                assertEquals(serialOf.getAsLong(), f.serial());
                assertTrue(f.before(fact2));
            });
        }

        @DirtiesContext
        @Test
        protected void testUniqueIdentConstraintInLog() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                String ident = UUID.randomUUID().toString();
                UUID id = UUID.randomUUID();
                UUID id2 = UUID.randomUUID();
                Fact f1 = Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\"" + id
                        + "\"],\"meta\":{\"unique_identifier\":\"" + ident + "\"}}", "{}");
                Fact f2 = Fact.of("{\"id\":\"" + id2
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\"" + id2
                        + "\"],\"meta\":{\"unique_identifier\":\"" + ident + "\"}}", "{}");
                uut.publish(f1);
                // needs to fail due to uniqueIdentitfier not being unique
                try {
                    uut.publish(f2);
                    fail("Expected IllegalArgumentException due to unique_identifier being used a sencond time");
                } catch (IllegalArgumentException e) {
                    // make sure, f1 was stored before
                    assertTrue(uut.fetchById(id).isPresent());
                }
            });
        }

        @DirtiesContext
        @Test
        protected void testUniqueIdentConstraintInBatch() {
            Assertions.assertTimeout(Duration.ofMillis(30000), () -> {
                String ident = UUID.randomUUID().toString();
                UUID id = UUID.randomUUID();
                UUID id2 = UUID.randomUUID();
                Fact f1 = Fact.of("{\"id\":\"" + id
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\"" + id
                        + "\"],\"meta\":{\"unique_identifier\":\"" + ident + "\"}}", "{}");
                Fact f2 = Fact.of("{\"id\":\"" + id2
                        + "\",\"type\":\"someType\",\"ns\":\"default\",\"aggIds\":[\"" + id2
                        + "\"],\"meta\":{\"unique_identifier\":\"" + ident + "\"}}", "{}");
                // needs to fail due to uniqueIdentitfier not being unique
                try {
                    uut.publish(Arrays.asList(f1, f2));
                    fail("Expected IllegalArgumentException due to unique_identifier being used twice in a batch");
                } catch (IllegalArgumentException e) {
                    // make sure, f1 was not stored either
                    assertFalse(uut.fetchById(id).isPresent());
                }
            });
        }

        @Test
        protected void testChecksMandatoryNamespaceOnPublish() {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                uut.publish(Fact.of("{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"someType\"}",
                        "{}"));
            });
        }

        @Test
        protected void testChecksMandatoryIdOnPublish() {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                uut.publish(Fact.of("{\"ns\":\"default\",\"type\":\"someType\"}", "{}"));
            });
        }

        @Test
        protected void testEnumerateNameSpaces() {
            // no namespaces
            assertEquals(0, uut.enumerateNamespaces().size());
            uut.publish(Fact.builder().ns("ns1").build("{}"));
            uut.publish(Fact.builder().ns("ns2").build("{}"));
            Set<String> ns = uut.enumerateNamespaces();
            assertEquals(2, ns.size());
            assertTrue(ns.contains("ns1"));
            assertTrue(ns.contains("ns2"));
        }

        @Test
        protected void testEnumerateTypesNull() {
            Assertions.assertThrows(NullPointerException.class, () -> {
                uut.enumerateTypes(null);
            });
        }

        @Test
        protected void testEnumerateTypes() {
            uut.publish(Fact.builder().ns("ns1").type("t1").build("{}"));
            uut.publish(Fact.builder().ns("ns2").type("t2").build("{}"));
            assertEquals(1, uut.enumerateTypes("ns1").size());
            assertTrue(uut.enumerateTypes("ns1").contains("t1"));
            uut.publish(Fact.builder().ns("ns1").type("t1").build("{}"));
            uut.publish(Fact.builder().ns("ns1").type("t1").build("{}"));
            uut.publish(Fact.builder().ns("ns1").type("t2").build("{}"));
            uut.publish(Fact.builder().ns("ns1").type("t3").build("{}"));
            uut.publish(Fact.builder().ns("ns1").type("t2").build("{}"));
            assertEquals(3, uut.enumerateTypes("ns1").size());
            assertTrue(uut.enumerateTypes("ns1").contains("t1"));
            assertTrue(uut.enumerateTypes("ns1").contains("t2"));
            assertTrue(uut.enumerateTypes("ns1").contains("t3"));
        }

        @Test
        protected void testFollow() throws Exception {
            uut.publish(newFollowTestFact());
            AtomicReference<CountDownLatch> l = new AtomicReference<>(new CountDownLatch(1));
            SubscriptionRequest request = SubscriptionRequest.follow(FactSpec.ns("followtest"))
                    .fromScratch();
            FactObserver observer = element -> l.get().countDown();
            uut.subscribeToFacts(request, observer);
            // make sure, you got the first one
            assertTrue(l.get().await(1500, TimeUnit.MILLISECONDS));

            l.set(new CountDownLatch(3));
            uut.publish(newFollowTestFact());
            uut.publish(newFollowTestFact());
            // needs to fail
            assertFalse(l.get().await(1500, TimeUnit.MILLISECONDS));
            assertEquals(1, l.get().getCount());

            uut.publish(newFollowTestFact());

            assertTrue(l.get().await(10, TimeUnit.SECONDS),
                    "failed to see all the facts published within 10 seconds.");
        }

        private Fact newFollowTestFact() {
            return Fact.builder().ns("followtest").id(UUID.randomUUID()).build("{}");
        }

        @Test
        public void testFetchByIdNullParameter() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                createStoreToTest().fetchById(null);
            });
        }

        @Test
        public void testPublishNullParameter() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                createStoreToTest().publish(null);
            });
        }

        @Test
        public void testSubscribeStartingAfter() throws Exception {
            for (int i = 0; i < 10; i++) {
                uut.publish(Fact.builder().id(new UUID(0L, i)).ns("ns1").type("t1").build("{}"));
            }

            ToListObserver toListObserver = new ToListObserver();

            SubscriptionRequest request = SubscriptionRequest.catchup(FactSpec.ns("ns1"))
                    .from(new UUID(
                            0L, 7L));
            Subscription s = uut.subscribeToFacts(request, toListObserver);
            s.awaitComplete();

            assertEquals(2, toListObserver.list().size());

        }

        @Test
        public void testSubscribeToIdsParameterContract() throws Exception {
            IdObserver observer = mock(IdObserver.class);
            assertThrows(NullPointerException.class, () -> {
                uut.subscribeToIds(null, observer);
            });
            assertThrows(NullPointerException.class, () -> {
                uut.subscribeToIds(mock(SubscriptionRequestTO.class), null);
            });
        }

        @Test
        public void testSubscribeToFactsParameterContract() throws Exception {
            FactObserver observer = mock(FactObserver.class);
            assertThrows(NullPointerException.class, () -> {
                uut.subscribeToFacts(null, observer);
            });
            assertThrows(NullPointerException.class, () -> {
                uut.subscribeToFacts(mock(SubscriptionRequestTO.class), null);
            });
        }

    }

    @Nested
    class OptimisticLocking {
        final String NS = "ns1";

        private Fact fact(UUID aggId) {
            return Fact.builder()
                    .ns(NS)
                    .type("t1")
                    .aggId(aggId)
                    .build("{}");
        }

        private List<Fact> catchup() {
            return catchup(FactSpec.ns(NS));
        }

        private List<Fact> catchup(FactSpec s) {

            LinkedList<Fact> l = new LinkedList<>();
            FactObserver o = f -> l.add(f);
            uut.subscribeToFacts(SubscriptionRequest.catchup(s).fromScratch(), o)
                    .awaitCatchup();
            return l;
        }

        @Test
        void happyPath() throws Exception {

            // setup
            UUID agg1 = UUID.randomUUID();
            uut.publish(fact(agg1));

            UUID ret = uut.lock(NS).on(agg1).optimistic().attempt(() -> {
                return Attempt.publish(fact(agg1));
            });

            verify(store).publishIfUnchanged(any(), any());
            assertThat(catchup()).hasSize(2);
            assertThat(ret).isNotNull();

        }

        @Test
        void happyPathWithEmptyStore() throws Exception {

            // setup
            UUID agg1 = UUID.randomUUID();

            UUID ret = uut.lock(NS).on(agg1).optimistic().attempt(() -> {
                return Attempt.publish(fact(agg1));
            });

            verify(store).publishIfUnchanged(any(), any());
            assertThat(catchup()).hasSize(1);
            assertThat(ret).isNotNull();

        }

        @Test
        void npeOnNamespaceMissing() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                uut.lock(null);
            });
        }

        @Test
        void npeOnNamespaceEmpty() throws Exception {
            assertThrows(IllegalArgumentException.class, () -> {
                uut.lock("");
            });
        }

        @Test
        void npeOnAggIdMissing() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                uut.lock("foo").on(null);
            });
        }

        @Test
        void npeOnAttemptIsNull() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                uut.lock("foo").on(UUID.randomUUID()).optimistic().attempt(null);
            });
        }

        @Test
        void npeOnAttemptReturningNull() throws Exception {
            assertThrows(NullPointerException.class, () -> {
                uut.lock("foo").on(UUID.randomUUID()).optimistic().attempt(() -> {
                    return null;
                });
            });
        }

        @Test
        void happyPathWithMoreThanOneAggregate() throws Exception {

            // setup
            UUID agg1 = UUID.randomUUID();
            UUID agg2 = UUID.randomUUID();
            uut.publish(fact(agg1));

            UUID ret = uut.lock(NS).on(agg1, agg2).optimistic().attempt(() -> {
                return Attempt.publish(fact(agg1));
            });

            verify(store).publishIfUnchanged(any(), any());
            assertThat(catchup()).hasSize(2);
            assertThat(ret).isNotNull();

        }

        @Test
        void happyPathWithMoreThanOneAggregateAndRetry() throws Exception {

            // setup
            UUID agg1 = UUID.randomUUID();
            UUID agg2 = UUID.randomUUID();
            uut.publish(fact(agg1));
            uut.publish(fact(agg2));

            CountDownLatch c = new CountDownLatch(8);

            UUID ret = uut.lock(NS).on(agg1, agg2).optimistic().retry(100).attempt(() -> {

                if (c.getCount() > 0) {
                    c.countDown();

                    if (Math.random() < 0.5)
                        uut.publish(fact(agg1));
                    else
                        uut.publish(fact(agg2));

                }

                return Attempt.publish(fact(agg2));
            });

            assertThat(catchup()).hasSize(11); // 8 conflicting, 2 initial and 1
                                               // from Attempt
            assertThat(ret).isNotNull();

            // publishing was properly blocked
            assertThat(c.getCount()).isEqualTo(0);

        }

        @Test
        void shouldThrowAttemptAbortedException() {
            assertThrows(AttemptAbortedException.class, () -> {
                uut.lock(NS).on(UUID.randomUUID()).optimistic().attempt(() -> {
                    return Attempt.abort("don't want to");
                });
            });
        }

        @Test
        void shouldNotExecuteAndThenDueToAbort() {

            Runnable e = mock(Runnable.class);

            assertThrows(AttemptAbortedException.class, () -> {
                uut.lock(NS).on(UUID.randomUUID()).attempt(() -> {
                    return Attempt.abort("don't want to").andThen(e);
                });
            });

            verify(e, never()).run();
        }

        @Test
        void shouldExecuteAndThen() throws OptimisticRetriesExceededException,
                ExceptionAfterPublish,
                AttemptAbortedException {

            Runnable e = mock(Runnable.class);

            uut.lock(NS).on(UUID.randomUUID()).attempt(() -> {
                return Attempt.publish(fact(UUID.randomUUID())).andThen(e);
            });

            verify(e).run();
        }

        @Test
        void shouldThrowCorrectExceptionOnFailureOfAndThen()
                throws OptimisticRetriesExceededException, ExceptionAfterPublish,
                AttemptAbortedException {

            Runnable e = mock(Runnable.class);
            Mockito.doThrow(NumberFormatException.class).when(e).run();

            assertThrows(ExceptionAfterPublish.class, () -> {
                uut.lock(NS).on(UUID.randomUUID()).attempt(() -> {
                    return Attempt.publish(fact(UUID.randomUUID())).andThen(e);
                });
            });
            verify(e).run();
        }

        @Test
        void shouldExecuteAndThenOnlyOnce() throws OptimisticRetriesExceededException,
                ExceptionAfterPublish,
                AttemptAbortedException {

            Runnable e = mock(Runnable.class);
            CountDownLatch c = new CountDownLatch(5);
            UUID agg1 = UUID.randomUUID();

            uut.lock(NS).on(agg1).optimistic().attempt(() -> {

                c.countDown();
                if (c.getCount() > 0) {
                    Fact conflictingFact = fact(agg1);
                    uut.publish(conflictingFact);
                }
                return Attempt.publish(fact(agg1)).andThen(e);
            });

            // there were many attempts
            assertThat(c.getCount()).isEqualTo(0);
            // but andThen is called only once
            verify(e, times(1)).run();

        }

        @Test
        void shouldThrowAttemptAbortedException_withMessage() {
            try {
                uut.lock(NS).on(UUID.randomUUID()).attempt(() -> {
                    return Attempt.abort("don't want to");
                });
                fail("should not have gotten here");
            } catch (AttemptAbortedException e) {
                assertThat(e.getMessage()).isEqualTo("don't want to");
            }

        }

        @Getter
        class MyAbortException extends AttemptAbortedException {

            private static final long serialVersionUID = 1L;

            private int i;

            public MyAbortException(int i) {
                super("nah");
                this.i = i;
            }

        }

        @Test
        void shouldPassCustomAbortedException() throws Exception {

            UUID agg1 = UUID.randomUUID();

            try {
                uut.lock(NS).on(agg1).optimistic().attempt(() -> {
                    throw new MyAbortException(42);
                });
                fail("should not have gotten here");
            } catch (AttemptAbortedException e) {
                assertThat(e.getMessage()).isEqualTo("nah");
                assertThat(e).isInstanceOf(MyAbortException.class);
                assertThat(((MyAbortException) e).i()).isEqualTo(42);
            }

        }

        @Test
        void shouldNotBeBlockedByUnrelatedFact() throws Exception {

            UUID agg1 = UUID.randomUUID();
            UUID agg2 = UUID.randomUUID();

            uut.lock(NS).on(agg1).optimistic().attempt(() -> {

                // write unrelated fact first
                uut.publish(fact(agg2));

                return Attempt.publish(fact(agg1));
            });

            assertThat(catchup()).hasSize(2);

        }

        @Test
        void shouldThrowRetriesExceededException() throws Exception {

            UUID agg1 = UUID.randomUUID();

            assertThrows(OptimisticRetriesExceededException.class, () -> {
                uut.lock(NS).on(agg1).optimistic().attempt(() -> {

                    // write conflicting fact first
                    uut.publish(fact(agg1));

                    return Attempt.publish(fact(agg1));
                });
            });
        }

        @Test
        void shouldReturnIdOfLastFactPublished() throws Exception {

            UUID agg1 = UUID.randomUUID();

            UUID expected = UUID.randomUUID();

            UUID lastFactId = uut.lock(NS).on(agg1).optimistic().attempt(() -> {

                Fact lastFact = Fact.builder().ns(NS).id(expected).build("{}");
                return Attempt.publish(fact(agg1), fact(agg1), fact(agg1), lastFact);

            });

            List<Fact> all = catchup();
            assertThat(all).hasSize(4);
            assertThat(all.get(all.size() - 1).id()).isEqualTo(expected);
            assertThat(lastFactId).isEqualTo(expected);

        }
    }

    static class ToListObserver implements FactObserver {
        @Getter
        private List<Fact> list = new LinkedList<>();

        @Override
        public void onNext(Fact element) {
            list.add(element);
        }

    }

    private static class TestFactObserver implements FactObserver {

        private final List<Fact> values = new CopyOnWriteArrayList<>();

        @Override
        public void onNext(Fact element) {
            values.add(element);
        }

        @SneakyThrows
        public void await(int count) {
            while (true) {
                if (values.size() >= count) {
                    return;
                } else {
                    Thread.sleep(50);
                }
            }
        }
    }
}
