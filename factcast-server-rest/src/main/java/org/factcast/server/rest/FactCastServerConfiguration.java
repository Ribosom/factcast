package org.factcast.server.rest;

import org.factcast.core.store.FactStore;
import org.factcast.server.rest.resources.EventsResource;
import org.factcast.server.rest.resources.EventsTransactionsResource;
import org.factcast.server.rest.resources.RootResource;
import org.factcast.store.inmem.InMemFactStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mercateo.common.rest.schemagen.link.LinkFactory;
import com.mercateo.common.rest.schemagen.link.LinkMetaFactory;
import com.mercateo.common.rest.schemagen.plugin.FieldCheckerForSchema;
import com.mercateo.common.rest.schemagen.plugin.MethodCheckerForLink;
import com.mercateo.rest.schemagen.spring.JerseyHateoasConfiguration;

@Configuration
@SuppressWarnings("deprecation")
@Import(JerseyHateoasConfiguration.class)
public class FactCastServerConfiguration {

    private InMemFactStore inMemFactStore = new InMemFactStore();

    @Bean
    FieldCheckerForSchema fieldCheckerForSchema() {
        return (field, callContext) -> true;
    }

    @Bean
    MethodCheckerForLink methodCheckerForLink() {
        return (scope) -> true;
    }

    @Bean
    LinkFactory<RootResource> rootResourceLinkFactory(LinkMetaFactory linkMetaFactory) {
        return linkMetaFactory.createFactoryFor(RootResource.class);
    }

    @Bean
    LinkFactory<EventsTransactionsResource> transactionsResourceLinkFactory(
            LinkMetaFactory linkMetaFactory) {
        return linkMetaFactory.createFactoryFor(EventsTransactionsResource.class);
    }

    @Bean
    LinkFactory<EventsResource> eventsResourceLinkFactory(LinkMetaFactory linkMetaFactory) {
        return linkMetaFactory.createFactoryFor(EventsResource.class);
    }

    @Bean
    FactStore getFactStore() {
        return inMemFactStore;
    }
}