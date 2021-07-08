/*-
 * #%L
 * deegree-ogcapi-features - OGC API Features (OAF) implementation - Querying and modifying of geospatial data objects
 * %%
 * Copyright (C) 2019 - 2020 lat/lon GmbH, info@lat-lon.de, www.lat-lon.de
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */
package org.deegree.services.oaf.workspace;

import org.deegree.cs.exceptions.UnknownCRSException;
import org.deegree.cs.persistence.CRSManager;
import org.deegree.feature.persistence.FeatureStore;
import org.deegree.feature.persistence.FeatureStoreException;
import org.deegree.feature.persistence.query.Query;
import org.deegree.feature.stream.FeatureInputStream;
import org.deegree.feature.types.FeatureType;
import org.deegree.filter.FilterEvaluationException;
import org.deegree.services.oaf.domain.collections.Collection;
import org.deegree.services.oaf.domain.collections.Collections;
import org.deegree.services.oaf.exceptions.InternalQueryException;
import org.deegree.services.oaf.exceptions.InvalidConfigurationException;
import org.deegree.services.oaf.exceptions.InvalidParameterValue;
import org.deegree.services.oaf.exceptions.UnknownCollectionId;
import org.deegree.services.oaf.feature.FeatureResponse;
import org.deegree.services.oaf.feature.FeatureResponseBuilder;
import org.deegree.services.oaf.feature.FeaturesRequest;
import org.deegree.services.oaf.link.Link;
import org.deegree.services.oaf.link.LinkBuilder;
import org.deegree.services.oaf.link.NextLink;
import org.deegree.services.oaf.workspace.configuration.FeatureTypeMetadata;
import org.deegree.services.oaf.workspace.configuration.OafDatasetConfiguration;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.deegree.services.oaf.OgcApiFeaturesConstants.DEFAULT_CRS;
import static org.deegree.services.oaf.workspace.DeegreeQueryBuilder.FIRST;
import static org.deegree.services.oaf.workspace.DeegreeQueryBuilder.UNLIMITED;

/**
 * @author <a href="mailto:goltz@lat-lon.de">Lyn Goltz </a>
 */
public class DeegreeDataAccess implements DataAccess {

    @Override
    public Collections createCollections( OafDatasetConfiguration oafConfiguration, LinkBuilder linkBuilder ) {
        String datasetId = oafConfiguration.getId();
        List<Link> links = linkBuilder.createCollectionsLinks( datasetId, oafConfiguration.getServiceMetadata() );
        List<Collection> collections = createCollectionList( oafConfiguration, datasetId, linkBuilder );
        return new Collections( links, collections );
    }

    @Override
    public Collection createCollection( OafDatasetConfiguration oafConfiguration, String collectionId,
                                        LinkBuilder linkBuilder )
                    throws UnknownCollectionId {
        String datasetId = oafConfiguration.getId();
        FeatureTypeMetadata featureTypeMetadata = oafConfiguration.getFeatureTypeMetadata( collectionId );
        return createCollection( oafConfiguration, datasetId, featureTypeMetadata, linkBuilder );
    }

    @Override
    public FeatureResponse retrieveFeatures( OafDatasetConfiguration oafConfiguration, String collectionId,
                                             FeaturesRequest featuresRequest,
                                             LinkBuilder linkBuilder )
                    throws UnknownCollectionId, InternalQueryException, InvalidParameterValue {
        FeatureTypeMetadata featureTypeMetadata = oafConfiguration.getFeatureTypeMetadata( collectionId );
        String crs = validateAndRetrieveCrs( featuresRequest.getResponseCrs() );
        FeatureStore featureStore = oafConfiguration.getFeatureStore( featureTypeMetadata.getName(), collectionId );
        try {
            DeegreeQueryBuilder queryBuilder = new DeegreeQueryBuilder( oafConfiguration );
            Query query = queryBuilder.createQuery( featureTypeMetadata, featuresRequest );
            return retrieveFeatures( oafConfiguration, featureTypeMetadata, collectionId, featuresRequest, linkBuilder,
                                     crs, featureStore,
                                     query );
        } catch ( FeatureStoreException | FilterEvaluationException | InvalidConfigurationException e ) {
            throw new InternalQueryException( e );
        }
    }

    @Override
    public FeatureResponse retrieveFeature( OafDatasetConfiguration oafConfiguration, String collectionId,
                                            String featureId, String responseCrs,
                                            LinkBuilder linkBuilder )
                    throws InternalQueryException, InvalidParameterValue, UnknownCollectionId {
        FeatureTypeMetadata featureTypeMetadata = oafConfiguration.getFeatureTypeMetadata( collectionId );
        String crs = validateAndRetrieveCrs( responseCrs );
        FeatureStore featureStore = oafConfiguration.getFeatureStore( featureTypeMetadata.getName(), collectionId );
        try {
            DeegreeQueryBuilder queryBuilder = new DeegreeQueryBuilder( oafConfiguration );
            Query queryById = queryBuilder.createQueryById( featureTypeMetadata.getName(), featureId );
            FeatureInputStream feature = featureStore.query( queryById );
            String datasetId = oafConfiguration.getId();
            List<Link> links = linkBuilder.createFeatureLinks( datasetId, collectionId, featureId );
            String schemaLocation = linkBuilder.createSchemaLink( datasetId, collectionId);
            Map<String, String> featureTypeNsPrefixes = getFeatureTypeNsPrefixes( featureStore );
            String namespaceURI = featureTypeMetadata.getName().getNamespaceURI();
            return new FeatureResponseBuilder( feature ).withFeatureTypeNsPrefixes(
                            featureTypeNsPrefixes ).withNumberOfFeatures( 1 ).withNumberOfFeaturesMatched(
                            1 ).withStartIndex( 0 ).withLinks(
                            links ).withMaxFeaturesAndStartIndexApplicable(
                            true ).withResponseCrsName( crs ).withSchemaLocation(
                            namespaceURI, schemaLocation ).build();
        } catch ( FeatureStoreException | FilterEvaluationException e ) {
            throw new InternalQueryException( e );
        }
    }

    private String validateAndRetrieveCrs( String crs )
                    throws InvalidParameterValue {
        if ( crs == null || crs.isEmpty() )
            return DEFAULT_CRS;
        try {
            CRSManager.lookup( crs );
        } catch ( UnknownCRSException e ) {
            throw new InvalidParameterValue( "crs", "Unknown CRS " + crs );
        }
        return crs;
    }

    private FeatureResponse retrieveFeatures( OafDatasetConfiguration oafConfiguration,
                                              FeatureTypeMetadata featureTypeMetadata, String collectionId,
                                              FeaturesRequest featuresRequest, LinkBuilder linkBuilder, String crs,
                                              FeatureStore featureStore, Query query )
                    throws FeatureStoreException, FilterEvaluationException {
        int numberOfFeaturesMatched = featureStore.queryHits( query );
        FeatureInputStream features = featureStore.query( query );
        boolean isMaxFeaturesAndStartIndexApplicable = featureStore.isMaxFeaturesAndStartIndexApplicable(
                        new Query[] { query } );
        if ( featuresRequest.isBulkUpload() ) {
            return retrieveFeaturesBulk( oafConfiguration, featureTypeMetadata, collectionId, linkBuilder, crs,
                                         featureStore,
                                         numberOfFeaturesMatched, features, isMaxFeaturesAndStartIndexApplicable );
        }
        return retrieveFeaturesLimitedNumber( oafConfiguration, featureTypeMetadata, collectionId, featuresRequest,
                                              linkBuilder, crs,
                                              featureStore, numberOfFeaturesMatched, features,
                                              isMaxFeaturesAndStartIndexApplicable );
    }

    private FeatureResponse retrieveFeaturesBulk( OafDatasetConfiguration oafConfiguration,
                                                  FeatureTypeMetadata featureTypeMetadata, String collectionId,
                                                  LinkBuilder linkBuilder, String crs, FeatureStore featureStore,
                                                  int numberOfFeaturesMatched, FeatureInputStream features,
                                                  boolean isMaxFeaturesAndStartIndexApplicable ) {
        int limit = UNLIMITED;
        int offset = FIRST;
        String datasetId = oafConfiguration.getId();
        List<Link> links = linkBuilder.createFeaturesLinks( datasetId, collectionId );
        Map<String, String> featureTypeNsPrefixes = getFeatureTypeNsPrefixes( featureStore );
        String schemaLocation = linkBuilder.createSchemaLink( datasetId, collectionId );
        String namespaceURI = featureTypeMetadata.getName().getNamespaceURI();
        return new FeatureResponseBuilder( features ).withFeatureTypeNsPrefixes(
                        featureTypeNsPrefixes ).withNumberOfFeatures( limit ).withNumberOfFeaturesMatched(
                        numberOfFeaturesMatched ).withStartIndex( offset ).withLinks(
                        links ).withMaxFeaturesAndStartIndexApplicable(
                        isMaxFeaturesAndStartIndexApplicable ).withResponseCrsName( crs ).withSchemaLocation(
                        namespaceURI, schemaLocation ).build();
    }

    private FeatureResponse retrieveFeaturesLimitedNumber( OafDatasetConfiguration oafConfiguration,
                                                           FeatureTypeMetadata featureTypeMetadata,
                                                           String collectionId, FeaturesRequest featuresRequest,
                                                           LinkBuilder linkBuilder, String crs,
                                                           FeatureStore featureStore, int numberOfFeaturesMatched,
                                                           FeatureInputStream features,
                                                           boolean isMaxFeaturesAndStartIndexApplicable ) {
        int limit = featuresRequest.getLimit();
        int offset = featuresRequest.getOffset();
        NextLink nextLink = new NextLink( numberOfFeaturesMatched, limit, offset );
        String datasetId = oafConfiguration.getId();
        List<Link> links = linkBuilder.createFeaturesLinks( datasetId, collectionId, nextLink );
        Map<String, String> featureTypeNsPrefixes = getFeatureTypeNsPrefixes( featureStore );
        String schemaLocation = linkBuilder.createSchemaLink( datasetId, collectionId );
        String namespaceURI = featureTypeMetadata.getName().getNamespaceURI();
        return new FeatureResponseBuilder( features ).withFeatureTypeNsPrefixes(
                        featureTypeNsPrefixes ).withNumberOfFeatures( limit ).withNumberOfFeaturesMatched(
                        numberOfFeaturesMatched ).withStartIndex( offset ).withLinks(
                        links ).withMaxFeaturesAndStartIndexApplicable(
                        isMaxFeaturesAndStartIndexApplicable ).withResponseCrsName( crs ).withSchemaLocation(
                        namespaceURI, schemaLocation ).build();
    }

    private List<Collection> createCollectionList( OafDatasetConfiguration oafConfiguration, String datasetId,
                                                   LinkBuilder linkBuilder ) {
        Map<String, FeatureTypeMetadata> featureTypeNames = oafConfiguration.getFeatureTypeMetadata();
        List<Collection> collectionList = new ArrayList<>( featureTypeNames.size() );
        for ( Map.Entry<String, FeatureTypeMetadata> entry : featureTypeNames.entrySet() ) {
            collectionList.add( createCollection( oafConfiguration, datasetId, entry.getValue(), linkBuilder ) );
        }
        return collectionList;
    }

    private Collection createCollection( OafDatasetConfiguration oafConfiguration, String datasetId,
                                         FeatureTypeMetadata featureType, LinkBuilder linkBuilder ) {
        // TODO: name must be unique!
        String featureTypeId = featureType.getName().getLocalPart();
        List<Link> links = linkBuilder.createCollectionLinks( datasetId, featureTypeId, featureType.getMetadataUrls() );
        String title = featureType.getTitle();
        String description = featureType.getDescription();
        List<String> suppportedCrs = oafConfiguration.getSuppportedCrs();
        return new Collection( featureTypeId, title, description, links, featureType.getExtent(), suppportedCrs,
                               featureType.getStorageCrs() );
    }

    private Map<String, String> getFeatureTypeNsPrefixes( FeatureStore featureStore ) {
        Map<String, String> prefixToNs = new HashMap<String, String>();
        FeatureType[] featureTypes = featureStore.getSchema().getFeatureTypes();
        for ( FeatureType ft : featureTypes ) {
            QName ftName = ft.getName();
            if ( ftName.getPrefix() != null ) {
                prefixToNs.put( ftName.getPrefix(), ftName.getNamespaceURI() );
            }
        }
        return prefixToNs;
    }

}
