/*-
 * #%L
 * deegree-ogcapi-features - OGC API Features (OAF) implementation - Querying and modifying of geospatial data objects
 * %%
 * Copyright (C) 2019 - 2024 lat/lon GmbH, info@lat-lon.de, www.lat-lon.de
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
package org.deegree.services.oaf.cql2;

import static org.slf4j.LoggerFactory.getLogger;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.deegree.commons.tom.datetime.ISO8601Converter;
import org.deegree.commons.tom.primitive.BaseType;
import org.deegree.commons.tom.primitive.PrimitiveType;
import org.deegree.commons.tom.primitive.PrimitiveValue;
import org.deegree.cs.coordinatesystems.ICRS;
import org.deegree.filter.Expression;
import org.deegree.filter.expression.Literal;
import org.deegree.filter.expression.ValueReference;
import org.deegree.filter.spatial.Intersects;
import org.deegree.filter.spatial.SpatialOperator;
import org.deegree.filter.temporal.After;
import org.deegree.filter.temporal.TemporalOperator;
import org.deegree.geometry.Geometry;
import org.deegree.geometry.GeometryFactory;
import org.deegree.geometry.primitive.LineString;
import org.deegree.geometry.primitive.LinearRing;
import org.deegree.geometry.primitive.Point;
import org.deegree.geometry.primitive.Polygon;
import org.deegree.geometry.primitive.Ring;
import org.deegree.services.oaf.workspace.configuration.FilterProperty;
import org.slf4j.Logger;

/**
 * @author <a href="mailto:goltz@lat-lon.de">Lyn Goltz </a>
 */
public class Cql2FilterVisitor extends Cql2BaseVisitor {

	private static final Logger LOG = getLogger(Cql2FilterVisitor.class);

	private final ICRS filterCrs;

	private final List<FilterProperty> filterProperties;

	/**
	 * @param filterCrs never <code>null</code>
	 * @param filterProperties
	 */
	public Cql2FilterVisitor(ICRS filterCrs, List<FilterProperty> filterProperties) {
		this.filterCrs = filterCrs;
		this.filterProperties = filterProperties;
	}

	@Override
	public Object visitBooleanExpression(Cql2Parser.BooleanExpressionContext ctx) {
		int terms = ctx.booleanTerm().size();
		if (terms == 1) {
			return ctx.booleanTerm(0).accept(this);
		}
		throw new Cql2UnsupportedExpressionException("More than one booleanTerm are currently not supported.");
	}

	@Override
	public Object visitBooleanTerm(Cql2Parser.BooleanTermContext ctx) {
		int factors = ctx.booleanFactor().size();
		if (factors == 1) {
			return ctx.booleanFactor(0).accept(this);
		}
		throw new Cql2UnsupportedExpressionException("More than one booleanFactor are currently not supported.");
	}

	@Override
	public Object visitBooleanFactor(Cql2Parser.BooleanFactorContext ctx) {
		return ctx.booleanPrimary().accept(this);
	}

	@Override
	public Object visitBooleanPrimary(Cql2Parser.BooleanPrimaryContext ctx) {
		if (ctx.booleanExpression() != null)
			throw new Cql2UnsupportedExpressionException("booleanExpressions are currently not supported.");
		if (ctx.function() != null)
			throw new Cql2UnsupportedExpressionException("functions are currently not supported.");
		if (ctx.BooleanLiteral() != null)
			throw new Cql2UnsupportedExpressionException("BooleanLiterals are currently not supported.");
		return ctx.predicate().accept(this);
	}

	@Override
	public Object visitPredicate(Cql2Parser.PredicateContext ctx) {
		if (ctx.comparisonPredicate() != null)
			throw new Cql2UnsupportedExpressionException("comparisonPredicate are currently not supported.");
		if (ctx.temporalPredicate() != null)
			return ctx.temporalPredicate().accept(this);
		if (ctx.arrayPredicate() != null)
			throw new Cql2UnsupportedExpressionException("arrayPredicate are currently not supported.");
		return ctx.spatialPredicate().accept(this);
	}

	@Override
	public SpatialOperator visitSpatialPredicate(Cql2Parser.SpatialPredicateContext ctx) {
		String spatialFunctionType = ctx.SpatialFunction().getText().toUpperCase().substring(2);
		SpatialOperator.SubType type = SpatialOperator.SubType.valueOf(spatialFunctionType);
		switch (type) {
			case INTERSECTS:
				Expression propeName = (Expression) ctx.geomExpression().get(0).accept(this);
				Geometry geometry = (Geometry) ctx.geomExpression().get(1).accept(this);
				return new Intersects(propeName, geometry);
		}
		throw new Cql2UnsupportedExpressionException("Unsupported geometry type " + type);
	}

	@Override
	public Object visitPropertyName(Cql2Parser.PropertyNameContext ctx) {
		String text = ctx.getText();
		List<QName> filterPropWithSameLocalName = filterProperties.stream()
			.map(FilterProperty::getName)
			.filter(name -> name.getLocalPart().equals(text))
			.collect(Collectors.toList());
		if (!filterPropWithSameLocalName.isEmpty()) {
			if (filterPropWithSameLocalName.size() > 1)
				LOG.warn("Found multiple filter properties with name {}: {}. Use {}", text,
						filterPropWithSameLocalName.stream().map(QName::toString).collect(Collectors.joining()),
						filterPropWithSameLocalName.get(0));
			return new ValueReference(filterPropWithSameLocalName.get(0));
		}
		return new ValueReference(text, null);
	}

	@Override
	public Object visitGeomExpression(Cql2Parser.GeomExpressionContext ctx) {
		if (ctx.function() != null) {
			throw new Cql2UnsupportedExpressionException("functions are currently not supported as geomExpressions.");
		}
		if (ctx.propertyName() != null) {
			return ctx.propertyName().accept(this);
		}
		return ctx.spatialInstance().accept(this);
	}

	@Override
	public Point visitPointText(Cql2Parser.PointTextContext ctx) {
		return (Point) ctx.point().accept(this);
	}

	@Override
	public LineString visitLineStringText(Cql2Parser.LineStringTextContext ctx) {
		List<Point> points = new ArrayList<>();
		for (Cql2Parser.PointContext p : ctx.point()) {
			points.add((Point) p.accept(this));
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createLineString("ls", filterCrs, geometryFactory.createPoints(points));
	}

	@Override
	public LinearRing visitLinearRingText(Cql2Parser.LinearRingTextContext ctx) {
		List<Point> points = new ArrayList<>();
		for (Cql2Parser.PointContext p : ctx.point()) {
			points.add((Point) p.accept(this));
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createLinearRing("lr", filterCrs, geometryFactory.createPoints(points));
	}

	@Override
	public Object visitPolygonText(Cql2Parser.PolygonTextContext ctx) {
		LinearRing exteriorRing = null;
		List<Ring> interiorRings = new ArrayList<>();
		for (Cql2Parser.LinearRingTextContext linearRing : ctx.linearRingText()) {
			if (exteriorRing == null) {
				exteriorRing = (LinearRing) linearRing.accept(this);
			}
			else {
				interiorRings.add((LinearRing) linearRing.accept(this));
			}
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createPolygon("po", filterCrs, exteriorRing, interiorRings);
	}

	@Override
	public Object visitMultiPointText(Cql2Parser.MultiPointTextContext ctx) {
		List<Point> points = new ArrayList<>();
		for (Cql2Parser.PointTextContext pointTextContext : ctx.pointText()) {
			points.add((Point) pointTextContext.accept(this));
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createMultiPoint("mp", filterCrs, points);
	}

	@Override
	public Object visitMultiLineStringText(Cql2Parser.MultiLineStringTextContext ctx) {
		List<LineString> lineStrings = new ArrayList<>();
		for (Cql2Parser.LineStringTextContext lineStringTextContext : ctx.lineStringText()) {
			lineStrings.add((LineString) lineStringTextContext.accept(this));
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createMultiLineString("ml", filterCrs, lineStrings);
	}

	@Override
	public Object visitMultiPolygonText(Cql2Parser.MultiPolygonTextContext ctx) {
		List<Polygon> polygons = new ArrayList<>();
		for (Cql2Parser.PolygonTextContext polygonTextContext : ctx.polygonText()) {
			polygons.add((Polygon) polygonTextContext.accept(this));
		}
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createMultiPolygon("mpol", filterCrs, polygons);
	}

	@Override
	public Object visitGeometryCollectionText(Cql2Parser.GeometryCollectionTextContext ctx) {
		List<Geometry> geometries = new ArrayList<>();
		for (Cql2Parser.GeometryLiteralContext geometryLiteralContext : ctx.geometryLiteral()) {
			geometries.add((Geometry) geometryLiteralContext.accept(this));
		}
		return geometries;
	}

	@Override
	public Object visitGeometryCollectionTaggedText(Cql2Parser.GeometryCollectionTaggedTextContext ctx) {
		List<Geometry> geometries = (List<Geometry>) ctx.geometryCollectionText().accept(this);
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createMultiGeometry("gc", filterCrs, geometries);
	}

	@Override
	public Object visitBboxTaggedText(Cql2Parser.BboxTaggedTextContext ctx) {
		return ctx.bboxText().accept(this);
	}

	@Override
	public Object visitBboxText(Cql2Parser.BboxTextContext ctx) {
		Double minX = Double.valueOf(ctx.westBoundLon().getText());
		Double minY = Double.valueOf(ctx.southBoundLat().getText());
		Double maxX = Double.valueOf(ctx.eastBoundLon().getText());
		Double maxY = Double.valueOf(ctx.northBoundLat().getText());
		GeometryFactory geometryFactory = new GeometryFactory();
		return geometryFactory.createEnvelope(minX, minY, maxX, maxY, filterCrs);
	}

	@Override
	public Point visitPoint(Cql2Parser.PointContext ctx) {
		GeometryFactory geometryFactory = new GeometryFactory();
		double x = (double) ctx.xCoord().accept(this);
		double y = (double) ctx.yCoord().accept(this);
		double z = ctx.zCoord() != null ? (double) ctx.zCoord().accept(this) : 0;
		return geometryFactory.createPoint("p", x, y, z, filterCrs);
	}

	@Override
	public Double visitXCoord(Cql2Parser.XCoordContext ctx) {
		return Double.valueOf(ctx.getText());
	}

	@Override
	public Double visitYCoord(Cql2Parser.YCoordContext ctx) {
		return Double.valueOf(ctx.getText());
	}

	@Override
	public Double visitZCoord(Cql2Parser.ZCoordContext ctx) {
		return Double.valueOf(ctx.getText());
	}

	@Override
	public Object visitTemporalPredicate(Cql2Parser.TemporalPredicateContext ctx) {
		String temporalFunctionType = ctx.TemporalFunction().getText().substring(2);
		TemporalOperator.SubType type = TemporalOperator.SubType.valueOf(temporalFunctionType);
		switch (type) {
			case AFTER:
				Expression propName = (Expression) ctx.temporalExpression(0).propertyName().accept(this);
				Expression dateValue = (Expression) ctx.temporalExpression(1).temporalInstance().accept(this);
				return new After(propName, dateValue);
		}
		throw new Cql2UnsupportedExpressionException("Unsupported geometry type " + type);
	}

	@Override
	public Object visitTemporalInstance(Cql2Parser.TemporalInstanceContext ctx) {
		if (ctx.intervalInstance() != null)
			throw new Cql2UnsupportedExpressionException("intervalInstance are currently not supported.");
		PrimitiveValue primitiveValue = (PrimitiveValue) ctx.instantInstance().accept(this);
		return new Literal<>(primitiveValue, null);
	}

	@Override
	public Object visitInstantInstance(Cql2Parser.InstantInstanceContext ctx) {
		if (ctx.dateInstant() != null) {
			Object date = ctx.dateInstant().accept(this);
			return new PrimitiveValue(date, new PrimitiveType(BaseType.DATE));
		}
		if (ctx.timestampInstant() != null) {
			Object dateTime = ctx.timestampInstant().accept(this);
			return new PrimitiveValue(dateTime, new PrimitiveType(BaseType.DATE_TIME));
		}
		return super.visitInstantInstance(ctx);
	}

	@Override
	public Object visitDateInstant(Cql2Parser.DateInstantContext ctx) {
		return ctx.dateInstantString().accept(this);
	}

	@Override
	public Object visitDateInstantString(Cql2Parser.DateInstantStringContext ctx) {
		return ISO8601Converter.parseDate(ctx.getText().substring(1, ctx.getText().length() - 1));
	}

	@Override
	public Object visitTimestampInstant(Cql2Parser.TimestampInstantContext ctx) {
		return ISO8601Converter.parseDateTime(ctx.getText().substring(11, ctx.getText().length() - 2));
	}

}
