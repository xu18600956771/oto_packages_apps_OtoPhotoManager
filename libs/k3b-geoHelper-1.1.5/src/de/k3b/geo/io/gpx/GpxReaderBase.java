/*
 * Copyright (c) 2015-2016 by k3b.
 *
 * This file is part of k3b-geoHelper library.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.k3b.geo.io.gpx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import de.k3b.geo.api.GeoPointDto;
import de.k3b.geo.api.IGeoInfoHandler;
import de.k3b.geo.io.GeoFormatter;
import de.k3b.geo.io.GeoUri;
import de.k3b.geo.io.GeoUriDef;
import de.k3b.util.IsoDateTimeParser;

/**
 * A parser for xml-geo formats.
 *
 * ![GpxReaderBase-parse](GpxReaderBase-parse.png)
 *
 * ```java
 *    GpxReaderBase parser = new GpxReaderBase(new IGeoInfoHandler() {
 *         public boolean onGeoInfo(IGeoPointInfo geo) {
 *           System.out.print(String.format("got lat=%f lon=%f\n", geo.getLatitude(),geo.getLongitude()));
 *           return true;
 *         }
 *      });
 *
 *    parser.parse(new InputSource(new FileReader( "test.gpx")));
 *
 * ```
 *
 * ---
 *
 * Supported formats:
 *
 * * [gpx-1.0](https://github.com/k3b/androFotoFinder/wiki/data#gpx10) and [gpx-1.1](https://github.com/k3b/androFotoFinder/wiki/data#gpx) files
 * * [kml-2.2](https://github.com/k3b/androFotoFinder/wiki/data#kml) files used by google
 * * [wikimedia](https://github.com/k3b/androFotoFinder/wiki/data#wikimedia) that is used by web-apis of wikipedia and wikivoyage
 * * [poi](https://github.com/k3b/androFotoFinder/wiki/data#poi) files, k3b-s internal xml format
 *
 * This parser is not acurate: it might pick elements from wrong namespaces.
 *
 * Note: if you change/add features to this xml parser
 * please also update regression test-data and prog at
 *
 * * ...\LocationMapViewer\k3b-geoHelper\src\test\resources\de\k3b\geo\io\regressionTests\*.*
 * * ...\LocationMapViewer\k3b-geoHelper\src\test\java\de.k3b.geo.io.GeoPointDtoRegressionTests.java
 *
 * Created by k3b on 20.01.2015.
 */
public class GpxReaderBase extends DefaultHandler {
    private static final Logger logger = LoggerFactory.getLogger(GpxReaderBase.class);

    /** Callback to process every point received */
    protected IGeoInfoHandler onGotNewWaypoint;

    /** If not null this instance is cleared and then reused for every new gpx found */
    protected final GeoPointDto mReuse;

    /** If not null gpx-v11: "trkpt" parsing is active */
    protected GeoPointDto current;

    /** This member will receive value of current xml-element while parsing */
    private StringBuffer currentXmlElementBufer = new StringBuffer();

    /** Used if xml contains geoUri attribute <poi geoUri='geo:...' /> to parse contained geo-uris..
     * it is Created on demand. */
    private GeoUri geoUriParser = null;

    /**
     * Creates a new parser.
     *
     * @param onGotNewWaypoint callback to process every point received
     */
    public GpxReaderBase(final IGeoInfoHandler onGotNewWaypoint) {
        this(onGotNewWaypoint, new GeoPointDto());
    }
    /**
     * Creates a new parser.
     *
     * @param onGotNewWaypoint callback to process every point received
     * @param reuse  if not null this instance is cleared and then reused for every new gpx found. This way the reader can load different implementations of {@link de.k3b.geo.api.IGeoPointInfo}
     */
    public GpxReaderBase(final IGeoInfoHandler onGotNewWaypoint, final GeoPointDto reuse) {
        this.onGotNewWaypoint = onGotNewWaypoint;
        this.mReuse = reuse;
    }

    /**
     * Processes gpx/kml/poi/xml data and calls [@link IGeoInfoHandler#onGeoInfo} for every
     * {@link de.k3b.geo.api.IGeoPointInfo} found.
     *
     * ![GpxReaderBase-parse](GpxReaderBase-parse.png)
     *
     * @startuml GpxReaderBase-parse.png
     * title process gpx/kml/poi/xml data
     * interface IGeoInfoHandler
     * IGeoInfoHandler : onGeoInfo(IGeoPointInfo)
     * IGeoInfoHandler <|-- GeoInfoHandlerImpl
     * GeoInfoHandlerImpl : onGeoInfo(IGeoPointInfo)
     *
     * class GpxReaderBase
     * GpxReaderBase : parse
     *
     * GpxReaderBase -> IGeoInfoHandler
     * InputSource -> GpxReaderBase : "file:points.gpx"
     * InputSource -> GpxReaderBase : "file:points.kml"
     * InputSource -> GpxReaderBase : "file:points.poi"
     * InputSource -> GpxReaderBase : "https://de.wikivoyage.org/w/api.php?..."
     * @enduml
     *
     */
    public void parse(InputSource in) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            // factory.setValidating(true);
            SAXParser parser = factory.newSAXParser();
            parser.parse(in, this);
        } catch (ParserConfigurationException e) {
            final String message = "Error parsing xml from " + in;
            logger.error(message, e);
            throw new IOException(message,e);
        } catch (SAXException e) {
            final String message = "Error parsing xml from " + in;
            logger.error(message, e);
            throw new IOException(message,e);
        }
    }

    /** Factory method: Returns an instance of an empty {@link de.k3b.geo.api.GeoPointDto} */
    protected GeoPointDto newInstance() {
        if (mReuse != null) return mReuse.clear();
        return new GeoPointDto();
    }

    /** Returns an instance of {@link de.k3b.geo.api.GeoPointDto}
     * and tries to data from the xml-attributes */
    protected GeoPointDto newInstance(Attributes attributes) {
        GeoPointDto result = newInstance();

        String geoUri=attributes.getValue(GeoUriDef.XML_ATTR_GEO_URI);
        if (geoUri != null) {
            String mode=attributes.getValue(GeoUriDef.XML_ATTR_GEO_URI_INFER_MISSING);
            if ((mode != null) || (this.geoUriParser == null)) {
                int modes = GeoUri.OPT_DEFAULT;

                if (mode != null) {
                    mode = mode.trim().toLowerCase();

                    if ((!mode.startsWith("0")) && (!mode.startsWith("f"))) {
                        // everything except "0" or "false" is infer
                        modes |= GeoUri.OPT_PARSE_INFER_MISSING;
                    }
                }
                this.geoUriParser = createGeoUriParser(modes);
            }
            geoUriParser.fromUri(geoUri, result);
        }

        // explicit attributes can overwrite values from geoUri=...
        String value = attributes.getValue(GeoUriDef.LAT_LON);
        if (value != null) GeoUri.parseLatOrLon(result, value);

        value = attributes.getValue(GeoUriDef.NAME);
        if (value != null) result.setName(value);

        value = attributes.getValue(GeoUriDef.DESCRIPTION);
        if (value != null) result.setDescription(value);

        value = attributes.getValue(GeoUriDef.ID);
        if (value != null) result.setId(value);

        value = attributes.getValue(GeoUriDef.LINK);
        if (value != null) result.setLink(value);

        value = attributes.getValue(GeoUriDef.SYMBOL);
        if (value != null) result.setSymbol(value);

        value = attributes.getValue(GeoUriDef.ZOOM);
        if (value != null) result.setZoomMin(GeoFormatter.parseZoom(value));

        value = attributes.getValue(GeoUriDef.ZOOM_MAX);
        if (value != null) result.setZoomMax(GeoFormatter.parseZoom(value));

        value = attributes.getValue(GeoUriDef.TIME);
        if (value != null) result.setTimeOfMeasurement(IsoDateTimeParser.parse(value));

        return result;
    }

    protected GeoUri createGeoUriParser(int modes) {
        return new GeoUri(modes);
    }

    /** Java sax api implementation: Element name inspection/processig */
    @Override
    public void startElement(String uri, String localName, String qName,
            Attributes attributes) throws SAXException {
        String name = getElementName(localName, qName);
        
        logger.debug("startElement {}-{}", localName, qName);
        if (name.equals(GpxDef_11.TRKPT) || name.equals(GpxDef_10.WPT)) {
            this.current = this.newInstance(attributes);
            final String lat = attributes.getValue(GpxDef_11.ATTR_LAT);
            if (lat != null) this.current.setLatitude(Double.parseDouble(lat));
            final String lon = attributes.getValue(GpxDef_11.ATTR_LON);
            if (lon != null) this.current.setLongitude(Double.parseDouble(lon));
        } else if (name.equals(WikimediaDef.COORDINATE)) {
            final String lat = attributes.getValue(GpxDef_11.ATTR_LAT);
            if (lat != null) this.current.setLatitude(Double.parseDouble(lat));
            final String lon = attributes.getValue(GpxDef_11.ATTR_LON);
            if (lon != null) this.current.setLongitude(Double.parseDouble(lon));
        } else if (name.equals(WikimediaDef.IMAGE)) {
            final String symbol = attributes.getValue(WikimediaDef.ATTR_IMAGE);
            if (symbol != null) this.current.setSymbol(symbol);
        } else if ((name.equals(KmlDef_22.PLACEMARK)) || (name.equals(GeoUriDef.XML_ELEMENT_POI))) {
            this.current = this.newInstance(attributes);
        } else if (name.equals(WikimediaDef.PAGE)) {
            this.current = this.newInstance(attributes);
            this.current.setId(attributes.getValue(WikimediaDef.ATTR_ID));
            this.current.setName(attributes.getValue(WikimediaDef.ATTR_TITLE));
            this.current.setLink(attributes.getValue(WikimediaDef.ATTR_LINK));
            final Date dateTime = IsoDateTimeParser.parse(attributes.getValue(WikimediaDef.ATTR_TIME));
            if (dateTime != null) {
                this.current.setTimeOfMeasurement(dateTime);
            }
        } else if ((this.current != null) && (name.equals(GpxDef_11.LINK) || name.equals(GpxDef_10.URL))) {
            this.current.setLink(attributes.getValue(GpxDef_11.ATTR_LINK));
        }
		if (this.current != null) {
			currentXmlElementBufer.setLength(0);
		}
    }

    /** Java sax api implementation: Element value and attribut inspection/processig */
    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        String name = getElementName(localName, qName);
        logger.debug("endElement {} {}", localName, qName);
        if (name.equals(GpxDef_11.TRKPT) || name.equals(GpxDef_10.WPT) || name.equals(KmlDef_22.PLACEMARK) || name.equals(GeoUriDef.XML_ELEMENT_POI) || name.equals(WikimediaDef.PAGE)) {
            GeoUri.inferMissing(this.current, this.current.getDescription());
            this.onGotNewWaypoint.onGeoInfo(this.current);
            this.current = null;
        } else if (this.current != null) {
            if (name.equals(GpxDef_11.NAME)) {
                this.current.setName(currentXmlElementBufer.toString());
            } else if (name.equals(GpxDef_11.DESC) || name.equals(KmlDef_22.DESCRIPTION)) {
                this.current.setDescription(currentXmlElementBufer.toString());
            } else if ((null == this.current.getLink()) && (name.equals(GpxDef_11.LINK) || name.equals(GpxDef_10.URL))) {
                this.current.setLink(currentXmlElementBufer.toString());
            } else if (name.equals(GeoUriDef.ID)) {
                this.current.setId(currentXmlElementBufer.toString());
            } else if (name.equals(GpxDef_11.TIME) || name.equals(KmlDef_22.TIMESTAMP_WHEN) || name.equals(KmlDef_22.TIMESPAN_BEGIN)) {
                final Date dateTime = IsoDateTimeParser.parse(currentXmlElementBufer.toString());
                if (dateTime != null) {
                    this.current.setTimeOfMeasurement(dateTime);
                } else {
                    saxError("/gpx//time or /kml//when or /kml//begin: invalid time "
                            + name +"=" + currentXmlElementBufer.toString());
                }

            } else if ((name.equals(KmlDef_22.COORDINATES) || name.equals(KmlDef_22.COORDINATES2)) && currentXmlElementBufer.length() > 0) {
                // <coordinates>lon,lat,height blank lon,lat,height ...</coordinates>
                try {
                    String parts[] = currentXmlElementBufer.toString().split("[,\\s]");
                    if ((parts != null) && (parts.length >= 2)) {
                        this.current.setLatitude(Double.parseDouble(parts[1]));
                        this.current.setLongitude(Double.parseDouble(parts[0]));
                    }
                } catch (NumberFormatException e) {
                    saxError("/kml//Placemark/Point/coordinates>Expected: 'lon,lat,...' but got "
                            + name +"=" + currentXmlElementBufer.toString());
                }
            }
        }
    }

    /** Called for every xml-sax-parser-error */
    private void saxError(String message) throws SAXException {
        throw new SAXException(message);
    }

    /** Get element-name removing possible namespace prefix */
    private String getElementName(String localName, String qName) {
        if ((localName != null) && (localName.length() > 0))
            return localName;
        if (qName == null) return "";

        int delim = qName.indexOf(":");
        if (delim < 0) return qName;

        return qName.substring(delim+1);
    }

    /** Java sax api implementation: Collect value while between start-element and end-element */
    @Override
    public void characters(char[] chars, int start, int length)
            throws SAXException {
		if (this.current != null) {
			currentXmlElementBufer.append(chars, start, length);
		}
    }
}
