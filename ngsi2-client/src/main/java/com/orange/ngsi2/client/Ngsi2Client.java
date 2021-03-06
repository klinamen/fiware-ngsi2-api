/*
 * Copyright (C) 2016 Orange
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.orange.ngsi2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orange.ngsi2.model.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureAdapter;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * NGSIv2 API Client
 */
public class Ngsi2Client {

    private final static Map<String, ?> noParams = Collections.emptyMap();

    private AsyncRestTemplate asyncRestTemplate;

    private HttpHeaders httpHeaders;

    private String baseURL;

    private Ngsi2Client() {
        // set default headers for Content-Type and Accept to application/JSON
        httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    }

    /**
     * Default constructor
     * @param asyncRestTemplate AsyncRestTemplate to handle requests
     * @param baseURL base URL for the NGSIv2 service
     */
    public Ngsi2Client(AsyncRestTemplate asyncRestTemplate, String baseURL) {
        this();
        this.asyncRestTemplate = asyncRestTemplate;
        this.baseURL = baseURL;

        // Inject NGSI2 error handler and Java 8 support
        injectNgsi2ErrorHandler();
        injectJava8ObjectMapper();
    }

    /**
     * @return the list of supported operations under /v2
     */
    public ListenableFuture<Map<String, String>> getV2() {
        ListenableFuture<ResponseEntity<JsonNode>> responseFuture = request(HttpMethod.GET, baseURL + "v2", null, JsonNode.class);
        return new ListenableFutureAdapter<Map<String, String>, ResponseEntity<JsonNode>>(responseFuture) {
            @Override
            protected Map<String, String> adapt(ResponseEntity<JsonNode> result) throws ExecutionException {
                Map<String, String> services = new HashMap<>();
                result.getBody().fields().forEachRemaining(entry -> services.put(entry.getKey(), entry.getValue().textValue()));
                return services;
            }
        };
    }

    /*
     * Entities requests
     */

    /**
     * Retrieve a list of Entities (simplified)
     * @param ids an optional list of entity IDs (cannot be used with idPatterns)
     * @param idPattern an optional pattern of entity IDs (cannot be used with ids)
     * @param types an optional list of types of entity
     * @param attrs an optional list of attributes to return for all entities
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of Entities
     */
    public ListenableFuture<Paginated<Entity>> getEntities(Collection<String> ids, String idPattern,
            Collection<String> types, Collection<String> attrs,
            int offset, int limit, boolean count) {

        return getEntities(ids, idPattern, types, attrs, null, null, null, offset, limit, count);
    }

    /**
     * Retrieve a list of Entities
     * @param ids an optional list of entity IDs (cannot be used with idPatterns)
     * @param idPattern an optional pattern of entity IDs (cannot be used with ids)
     * @param types an optional list of types of entity
     * @param attrs an optional list of attributes to return for all entities
     * @param query an optional Simple Query Language query
     * @param geoQuery an optional Geo query
     * @param orderBy an option list of attributes to difine the order of entities
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of Entities
     */
    public ListenableFuture<Paginated<Entity>> getEntities(Collection<String> ids, String idPattern,
                                                           Collection<String> types, Collection<String> attrs,
                                                           String query, GeoQuery geoQuery,
                                                           Collection<String> orderBy,
                                                           int offset, int limit, boolean count) {

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities");
        addParam(builder, "id", ids);
        addParam(builder, "idPattern", idPattern);
        addParam(builder, "type", types);
        addParam(builder, "attrs", attrs);
        addParam(builder, "query", query);
        addGeoQueryParams(builder, geoQuery);
        addParam(builder, "orderBy", orderBy);
        addPaginationParams(builder, offset, limit);
        if (count) {
            addParam(builder, "options", "count");
        }

        return adaptPaginated(request(HttpMethod.GET, builder.toUriString(), null, Entity[].class), offset, limit);
    }

    /**
     * Create a new entity
     * @param entity the Entity to add
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> addEntity(Entity entity) {
        return adapt(request(HttpMethod.POST, UriComponentsBuilder.fromHttpUrl(baseURL).path("v2/entities").toUriString(), entity, Void.class));
    }

    /**
     * Get an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attrs the list of attributes to retreive for this entity, null or empty means all attributes
     * @return the entity
     */
    public ListenableFuture<Entity> getEntity(String entityId, String type, Collection<String> attrs) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}");
        addParam(builder, "type", type);
        addParam(builder, "attrs", attrs);
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId).toUriString(), null, Entity.class));
    }

    /**
     * Update existing or append some attributes to an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributes the attributes to update or to append
     * @param append if true, will only allow to append new attributes
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> updateEntity(String entityId, String type, Map<String, Attribute> attributes, boolean append) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}");
        addParam(builder, "type", type);
        if (append) {
            addParam(builder, "options", "append");
        }
        return adapt(request(HttpMethod.POST, builder.buildAndExpand(entityId).toUriString(), attributes, Void.class));
    }

    /**
     * Replace all the existing attributes of an entity with a new set of attributes
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributes the new set of attributes
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> replaceEntity(String entityId, String type, Map<String, Attribute> attributes) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.PUT, builder.buildAndExpand(entityId).toUriString(), attributes, Void.class));
    }

    /**
     * Delete an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> deleteEntity(String entityId, String type) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(entityId).toUriString(), null, Void.class));
    }

    /*
     * Attributes requests
     */

    /**
     * Retrieve the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Attribute> getAttribute(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}/attrs/{attributeName}");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId, attributeName).toUriString(), null, Attribute.class));
    }

    /**
     * Update the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Void> updateAttribute(String entityId, String type, String attributeName, Attribute attribute) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}/attrs/{attributeName}");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.PUT, builder.buildAndExpand(entityId, attributeName).toUriString(), attribute, Void.class));
    }

    /**
     * Delete the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Attribute> deleteAttribute(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}/attrs/{attributeName}");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(entityId, attributeName).toUriString(), null, Attribute.class));
    }

    /*
     * Attribute values requests
     */

    /**
     * Retrieve the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<Object> getAttributeValue(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}/attrs/{attributeName}/value");
        addParam(builder, "type", type);
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId, attributeName).toUriString(), null, Object.class));
    }

    /**
     * Retrieve the attribute of an entity
     * @param entityId the entity ID
     * @param type optional entity type to avoid ambiguity when multiple entities have the same ID, null or zero-length for empty
     * @param attributeName the attribute name
     * @return
     */
    public ListenableFuture<String> getAttributeValueAsString(String entityId, String type, String attributeName) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/entities/{entityId}/attrs/{attributeName}/value");
        addParam(builder, "type", type);
        HttpHeaders httpHeaders = cloneHttpHeaders();
        httpHeaders.setAccept(Collections.singletonList(MediaType.TEXT_PLAIN));
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityId, attributeName).toUriString(), httpHeaders, null, String.class));
    }

    /*
     * Entity Type requests
     */

    /**
     * Retrieve a list of entity types
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of entity types
     */
    public ListenableFuture<Paginated<EntityType>> getEntityTypes(int offset, int limit, boolean count) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/types");
        addPaginationParams(builder, offset, limit);
        if (count) {
            addParam(builder, "options", "count");
        }
        return adaptPaginated(request(HttpMethod.GET, builder.toUriString(), null, EntityType[].class), offset, limit);
    }

    /**
     * Retrieve an entity type
     * @param entityType the entityType to retrieve
     * @return an entity type
     */
    public ListenableFuture<EntityType> getEntityType(String entityType) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/types/{entityType}");
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(entityType).toUriString(), null, EntityType.class));
    }

    /*
     * Registrations requests
     */

    /**
     * Retrieve the list of all Registrations
     * @return a list of registrations
     */
    public ListenableFuture<List<Registration>> getRegistrations() {

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/registrations");

        ListenableFuture<ResponseEntity<Registration[]>> e = request(HttpMethod.GET, builder.toUriString(), null, Registration[].class);
        return new ListenableFutureAdapter<List<Registration>, ResponseEntity<Registration[]>>(e) {
            @Override
            protected List<Registration> adapt(ResponseEntity<Registration[]> result) throws ExecutionException {
                return new ArrayList<>(Arrays.asList(result.getBody()));
            }
        };
    }

    /**
     * Create a new registration
     * @param registration the Registration to add
     * @return the listener to notify of completion
     */
    public ListenableFuture<Void> addRegistration(Registration registration) {
        return adapt(request(HttpMethod.POST, UriComponentsBuilder.fromHttpUrl(baseURL).path("v2/registrations").toUriString(), registration, Void.class));
    }

    /**
     * Retrieve the registration by registration ID
     * @param registrationId the registration ID
     * @return registration
     */
    public ListenableFuture<Registration> getRegistration(String registrationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/registrations/{registrationId}");
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(registrationId).toUriString(), null, Registration.class));
    }

    /**
     * Update the registration by registration ID
     * @param registrationId the registration ID
     * @return
     */
    public ListenableFuture<Void> updateRegistration(String registrationId, Registration registration) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/registrations/{registrationId}");
        return adapt(request(HttpMethod.PATCH, builder.buildAndExpand(registrationId).toUriString(), registration, Void.class));
    }

    /**
     * Delete the registration by registration ID
     * @param registrationId the registration ID
     * @return
     */
    public ListenableFuture<Void> deleteRegistration(String registrationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/registrations/{registrationId}");
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(registrationId).toUriString(), null, Void.class));
    }

    /*
     * Subscriptions requests
     */

    /**
     * Retrieve the list of all Subscriptions present in the system
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a pagined list of Subscriptions
     */
    public ListenableFuture<Paginated<Subscription>> getSubscriptions(int offset, int limit, boolean count) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/subscriptions");
        addPaginationParams(builder, offset, limit);
        if (count) {
            addParam(builder, "options", "count");
        }

        return adaptPaginated(request(HttpMethod.GET, builder.toUriString(), null, Subscription[].class), offset, limit);
    }

    /**
     * Create a new subscription
     * @param subscription the Subscription to add
     * @return subscription Id
     */
    public ListenableFuture<String> addSubscription(Subscription subscription) {
        ListenableFuture<ResponseEntity<Void>> s = request(HttpMethod.POST, UriComponentsBuilder.fromHttpUrl(baseURL).path("v2/subscriptions").toUriString(), subscription, Void.class);
        return new ListenableFutureAdapter<String, ResponseEntity<Void>>(s) {
            @Override
            protected String adapt(ResponseEntity<Void> result) throws ExecutionException {
                return extractId(result);
            }
        };
    }

    /**
     * Get a Subscription by subscription ID
     * @param subscriptionId the subscription ID
     * @return the subscription
     */
    public ListenableFuture<Subscription> getSubscription(String subscriptionId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/subscriptions/{subscriptionId}");
        return adapt(request(HttpMethod.GET, builder.buildAndExpand(subscriptionId).toUriString(), null, Subscription.class));
    }

    /**
     * Update the subscription by subscription ID
     * @param subscriptionId the subscription ID
     * @return
     */
    public ListenableFuture<Void> updateSubscription(String subscriptionId, Subscription subscription) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/subscriptions/{subscriptionId}");
        return adapt(request(HttpMethod.PATCH, builder.buildAndExpand(subscriptionId).toUriString(), subscription, Void.class));
    }

    /**
     * Delete the subscription by subscription ID
     * @param subscriptionId the subscription ID
     * @return
     */
    public ListenableFuture<Void> deleteSubscription(String subscriptionId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/subscriptions/{subscriptionId}");
        return adapt(request(HttpMethod.DELETE, builder.buildAndExpand(subscriptionId).toUriString(), null, Void.class));
    }

    /*
     * POJ RPC "bulk" Operations
     */

    /**
     * Update, append or delete multiple entities in a single operation
     * @param bulkUpdateRequest a BulkUpdateRequest with an actionType and a list of entities to update
     * @return Nothing on success
     */
    public ListenableFuture<Void> bulkUpdate(BulkUpdateRequest bulkUpdateRequest) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/op/update");
        return adapt(request(HttpMethod.POST, builder.toUriString(), bulkUpdateRequest, Void.class));
    }

    /**
     * Query multiple entities in a single operation
     * @param bulkQueryRequest defines the list of entities, attributes and scopes to match entities
     * @param orderBy an optional list of attributes to order the entities (null or empty for none)
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a paginated list of entities
     */
    public ListenableFuture<Paginated<Entity>> bulkQuery(BulkQueryRequest bulkQueryRequest, Collection<String> orderBy, int offset, int limit, boolean count) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/op/query");
        addPaginationParams(builder, offset, limit);
        addParam(builder, "orderBy", orderBy);
        if (count) {
            addParam(builder, "options", "count");
        }
        return adaptPaginated(request(HttpMethod.POST, builder.toUriString(), bulkQueryRequest, Entity[].class), offset, limit);
    }

    /**
     * Create, update or delete registrations to multiple entities in a single operation
     * @param bulkRegisterRequest defines the list of entities to register
     * @return a list of registration ids
     */
    public ListenableFuture<String[]> bulkRegister(BulkRegisterRequest bulkRegisterRequest) {
        return adapt(request(HttpMethod.POST, UriComponentsBuilder.fromHttpUrl(baseURL).path("v2/op/register").toUriString(), bulkRegisterRequest, String[].class));
    }

    /**
     * Discover registration matching entities and their attributes
     * @param bulkQueryRequest defines the list of entities, attributes and scopes to match registrations
     * @param offset an optional offset (0 for none)
     * @param limit an optional limit (0 for none)
     * @param count true to return the total number of matching entities
     * @return a paginated list of registration
     */
    public ListenableFuture<Paginated<Registration>> bulkDiscover(BulkQueryRequest bulkQueryRequest, int offset, int limit, boolean count) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseURL);
        builder.path("v2/op/discover");
        addPaginationParams(builder, offset, limit);
        if (count) {
            addParam(builder, "options", "count");
        }
        return adaptPaginated(request(HttpMethod.POST, builder.toUriString(), bulkQueryRequest, Registration[].class), offset, limit);
    }

    /**
     * Default headers
     * @return the default headers
     */
    public HttpHeaders getHttpHeaders() {
        return httpHeaders;
    }

    /**
     * Make an HTTP request with default headers
     */
    protected <T,U> ListenableFuture<ResponseEntity<T>> request(HttpMethod method, String uri, U body, Class<T> responseType) {
        return request(method, uri, getHttpHeaders(), body, responseType);
    }

    /**
     * Make an HTTP request with custom headers
     */
    protected <T,U> ListenableFuture<ResponseEntity<T>> request(HttpMethod method, String uri, HttpHeaders httpHeaders, U body, Class<T> responseType) {
        HttpEntity<U> requestEntity = new HttpEntity<>(body, httpHeaders);
        return asyncRestTemplate.exchange(uri, method, requestEntity, responseType);
    }

    private <T> ListenableFuture<T> adapt(ListenableFuture<ResponseEntity<T>> responseEntityListenableFuture) {
        return new ListenableFutureAdapter<T, ResponseEntity<T>>(responseEntityListenableFuture) {
            @Override
            protected T adapt(ResponseEntity<T> result) throws ExecutionException {
                return result.getBody();
            }
        };
    }

    private <T> ListenableFuture<Paginated<T>> adaptPaginated(ListenableFuture<ResponseEntity<T[]>> responseEntityListenableFuture, int offset, int limit) {
        return new ListenableFutureAdapter<Paginated<T>, ResponseEntity<T[]>>(responseEntityListenableFuture) {
            @Override
            protected Paginated<T> adapt(ResponseEntity<T[]> result) throws ExecutionException {
                return new Paginated<>(Arrays.asList(result.getBody()), offset, limit, extractTotalCount(result));
            }
        };
    }

    private void addPaginationParams(UriComponentsBuilder builder, int offset, int limit) {
        if (offset > 0) {
            builder.queryParam("offset", offset);
        }
        if (limit > 0) {
            builder.queryParam("limit", limit);
        }
    }

    private void addParam(UriComponentsBuilder builder, String key, String value) {
        if (!nullOrEmpty(value)) {
            builder.queryParam(key, value);
        }
    }

    private void addParam(UriComponentsBuilder builder, String key, Collection<? extends CharSequence> value) {
        if (!nullOrEmpty(value)) {
            builder.queryParam(key, String.join(",", value));
        }
    }

    private void addGeoQueryParams(UriComponentsBuilder builder, GeoQuery geoQuery) {
        if (geoQuery != null) {
            StringBuilder georel = new StringBuilder(geoQuery.getRelation().name());
            if (geoQuery.getRelation() == GeoQuery.Relation.near) {
                georel.append(';').append(geoQuery.getModifier());
                georel.append(':').append(geoQuery.getDistance());
            }
            builder.queryParam("georel", georel.toString());
            builder.queryParam("geometry", geoQuery.getGeometry());
            builder.queryParam("coords", geoQuery.getCoordinates().stream().map(Coordinate::toString).collect(Collectors.joining(";")));
        }
    }

    private boolean nullOrEmpty(Collection i) {
        return i == null || i.isEmpty();
    }

    private boolean nullOrEmpty(String i) {
        return i == null || i.isEmpty();
    }

    private int extractTotalCount(ResponseEntity responseEntity) {
        String total = responseEntity.getHeaders().getFirst("X-Total-Count");
        try {
            return Integer.parseInt(total);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractId(ResponseEntity responseEntity) {
        String location = responseEntity.getHeaders().getFirst("Location");
        String paths[] = location.split("/");
        if (paths != null && paths.length > 0) {
            return paths[paths.length - 1];
        }
        return "";
    }

    /**
     * @return return a clone HttpHeader from default HttpHeader
     */
    private HttpHeaders cloneHttpHeaders() {
        HttpHeaders httpHeaders = getHttpHeaders();
        HttpHeaders clone = new HttpHeaders();
        for (String entry : httpHeaders.keySet()) {
            clone.put(entry, httpHeaders.get(entry));
        }
        return clone;
    }

    /**
     * Inject the Ngsi2ResponseErrorHandler
     */
    protected void injectNgsi2ErrorHandler() {
        MappingJackson2HttpMessageConverter converter = getMappingJackson2HttpMessageConverter();
        if (converter != null) {
            this.asyncRestTemplate.setErrorHandler(new Ngsi2ResponseErrorHandler(converter.getObjectMapper()));
        }
    }

    /**
     * Inject an ObjectMapper supporting Java8 and JavaTime module by default
     */
    protected void injectJava8ObjectMapper() {
        MappingJackson2HttpMessageConverter converter = getMappingJackson2HttpMessageConverter();
        if (converter != null) {
            converter.getObjectMapper().registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
    }

    private MappingJackson2HttpMessageConverter getMappingJackson2HttpMessageConverter() {
        for(HttpMessageConverter httpMessageConverter : asyncRestTemplate.getMessageConverters()) {
            if (httpMessageConverter instanceof MappingJackson2HttpMessageConverter) {
                return (MappingJackson2HttpMessageConverter)httpMessageConverter;
            }
        }
        return null;
    }
}
