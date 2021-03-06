/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wms.capabilities;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DimensionInfo;
import org.geoserver.catalog.DimensionPresentation;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.util.ReaderDimensionsAccessor;
import org.geoserver.platform.ServiceException;
import org.geoserver.wms.WMS;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.factory.GeoTools;
import org.geotools.temporal.object.DefaultPeriodDuration;
import org.geotools.util.Converters;
import org.geotools.util.logging.Logging;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Helper class avoiding to duplicate the time/elevation management code between WMS 1.1 and 1.3
 * 
 * @author Andrea Aime - GeoSolutions
 */
abstract class DimensionHelper {

    static final Logger LOGGER = Logging.getLogger(DimensionHelper.class);

    enum Mode {
        WMS11, WMS13
    }

    Mode mode;
    WMS wms;

    public DimensionHelper(Mode mode, WMS wms) {
        this.mode = mode;
        this.wms = wms;
    }

    /**
     * Implement to write out an element
     */
    protected abstract void element(String element, String content);

    /**
     * Implement to write out an element
     */
    protected abstract void element(String element, String content, Attributes atts);

    void handleVectorLayerDimensions(LayerInfo layer) {
        // do we have time and elevation?
        FeatureTypeInfo typeInfo = (FeatureTypeInfo) layer.getResource();
        DimensionInfo timeInfo = typeInfo.getMetadata().get(ResourceInfo.TIME,
                DimensionInfo.class);
        DimensionInfo elevInfo = typeInfo.getMetadata().get(ResourceInfo.ELEVATION,
                DimensionInfo.class);
        boolean hasTime = timeInfo != null && timeInfo.isEnabled();
        boolean hasElevation = elevInfo != null && elevInfo.isEnabled();

        // skip if no need
        if (!hasTime && !hasElevation) {
            return;
        }

        if (mode == Mode.WMS11) {
            String elevUnits = hasElevation ? elevInfo.getUnits() : "";
            String elevUnitSymbol = hasElevation ? elevInfo.getUnitSymbol() : "";
            declareWMS11Dimensions(hasTime, hasElevation, elevUnits, elevUnitSymbol, null);
        }

        // Time dimension
        if (hasTime) {
            try {
                handleTimeDimensionVector(typeInfo);
            } catch (IOException e) {
                throw new RuntimeException("Failed to handle time attribute for layer", e);
            }
        }
        // elevation dimension
        if (hasElevation) {
            try {
                handleElevationDimensionVector(typeInfo);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Writes down the raster layer dimensions, if any
     * 
     * @param layer
     * @throws RuntimeException
     */
    void handleRasterLayerDimensions(final LayerInfo layer) throws RuntimeException {
        
        // do we have time and elevation?
        CoverageInfo cvInfo = (CoverageInfo) layer.getResource();
        if (cvInfo == null)
            throw new ServiceException("Unable to acquire coverage resource for layer: "
                    + layer.getName());

        DimensionInfo timeInfo = null;
        DimensionInfo elevInfo = null;
        Map<String, DimensionInfo> customDimensions = new HashMap<String, DimensionInfo>();
        AbstractGridCoverage2DReader reader = null;
        
        for (Map.Entry<String, Serializable> e : cvInfo.getMetadata().entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key.equals(ResourceInfo.TIME)) {
                timeInfo = Converters.convert(value, DimensionInfo.class);
            } else if (key.equals(ResourceInfo.ELEVATION)) {
                elevInfo = Converters.convert(value, DimensionInfo.class);
            } else if (value instanceof DimensionInfo) {
                DimensionInfo dimInfo = (DimensionInfo) value;
                if (dimInfo.isEnabled()) {
                    if (key.startsWith(ResourceInfo.CUSTOM_DIMENSION_PREFIX)) {
                        String dimensionName = key.substring(ResourceInfo.CUSTOM_DIMENSION_PREFIX
                                .length());
                        customDimensions.put(dimensionName, dimInfo);
                    } else {
                        LOGGER.log(Level.SEVERE, "Skipping custom  dimension with key " + key
                                + " since it does not start with "
                                + ResourceInfo.CUSTOM_DIMENSION_PREFIX);
                    }
                }
            }
        }
        boolean hasTime = timeInfo != null && timeInfo.isEnabled();
        boolean hasElevation = elevInfo != null && elevInfo.isEnabled();
        boolean hasCustomDimensions = !customDimensions.isEmpty();

        // skip if nothing is configured
        if (!hasTime && !hasElevation && !hasCustomDimensions) {
            return;
        }
        
        Catalog catalog = cvInfo.getCatalog();
        if (catalog == null)
            throw new ServiceException("Unable to acquire catalog resource for layer: "
                    + layer.getName());

        CoverageStoreInfo csinfo = cvInfo.getStore();
        if (csinfo == null)
            throw new ServiceException("Unable to acquire coverage store resource for layer: "
                    + layer.getName());

        try {
            reader = (AbstractGridCoverage2DReader) catalog.getResourcePool()
                    .getGridCoverageReader(csinfo, GeoTools.getDefaultHints());
        } catch (Throwable t) {
                 LOGGER.log(Level.SEVERE, "Unable to acquire a reader for this coverage with format: "
                                 + csinfo.getFormat().getName(), t);
        }
        if (reader == null) {
            throw new ServiceException("Unable to acquire a reader for this coverage with format: "
                    + csinfo.getFormat().getName());
        }
        ReaderDimensionsAccessor dimensions = new ReaderDimensionsAccessor(reader);
        
        // Process only custom dimensions supported by the reader
        if (hasCustomDimensions) {
            for (String key : customDimensions.keySet()) {
                if (!dimensions.hasDomain(key)) customDimensions.remove(key);
            }
        }
        
        if (mode == Mode.WMS11) {
            String elevUnits = hasElevation ? elevInfo.getUnits() : "";
            String elevUnitSymbol = hasElevation ? elevInfo.getUnitSymbol() : "";
            declareWMS11Dimensions(hasTime, hasElevation, elevUnits, elevUnitSymbol, customDimensions);
        }
        

        // timeDimension
        if (hasTime && dimensions.hasTime()) {
            handleTimeDimensionRaster(timeInfo, dimensions);
        }

        // elevationDomain
        if (hasElevation && dimensions.hasElevation()) {
            handleElevationDimensionRaster(elevInfo, dimensions);
        }
        
        // custom dimensions
        if (hasCustomDimensions) {
            for (String key : customDimensions.keySet()) {
                DimensionInfo dimensionInfo = customDimensions.get(key);
                handleCustomDimensionRaster(key, dimensionInfo, dimensions);
            }
        }
    }

    private void handleElevationDimensionRaster(DimensionInfo elevInfo, ReaderDimensionsAccessor dimensions) {
        TreeSet<Double> elevations = dimensions.getElevationDomain();
        String elevationMetadata = getZDomainRepresentation(elevInfo, elevations);

        writeElevationDimension(elevations, elevationMetadata, 
                elevInfo.getUnits(), elevInfo.getUnitSymbol());
    }

    private void handleTimeDimensionRaster(DimensionInfo timeInfo, ReaderDimensionsAccessor dimension) {
        TreeSet<Date> temporalDomain = dimension.getTimeDomain();
        String timeMetadata = getTemporalDomainRepresentation(timeInfo, temporalDomain);

        writeTimeDimension(timeMetadata);
    }
    
    private void handleCustomDimensionRaster(String dimName, DimensionInfo dimension,
            ReaderDimensionsAccessor dimAccessor) {
        final TreeSet<String> values = dimAccessor.getDomain(dimName);
        String metadata = getCustomDomainRepresentation(dimension, values);

        writeCustomDimension(dimName, metadata, values.first(), dimension.getUnits(), dimension.getUnitSymbol());
    }

    /**
     * Writes WMS 1.1.1 conforming dimensions (WMS 1.3 squashed dimensions and extent in the same tag instead)
     * @param hasTime - <tt>true</tt> if the layer has the time dimension, <tt>false</tt> otherwise
     * @param hasElevation - <tt>true</tt> if the layer has the elevation dimension, <tt>false</tt> otherwise
     * @param elevUnits - <tt>units</tt> attribute of the elevation dimension
     * @param elevUnitSymbol - <tt>unitSymbol</tt> attribute of the elevation dimension
     */
    private void declareWMS11Dimensions(boolean hasTime, boolean hasElevation, String elevUnits, String elevUnitSymbol, Map<String, DimensionInfo> customDimensions) {
        // we have to declare time and elevation before the extents
        if (hasTime) {
            AttributesImpl timeDim = new AttributesImpl();
            timeDim.addAttribute("", "name", "name", "", "time");
            timeDim.addAttribute("", "units", "units", "", "ISO8601");
            element("Dimension", null, timeDim);
        }
        if (hasElevation) {
            // same as WMS 1.3 except no values
            writeElevationDimensionElement(null, null, null, elevUnits, elevUnitSymbol);
        }
        if (customDimensions != null) {
            for (String dim : customDimensions.keySet()) {
                DimensionInfo di = customDimensions.get(dim);
                AttributesImpl custDim = new AttributesImpl();
                custDim.addAttribute("", "name", "name", "", dim);
                String units = di.getUnits();
                String unitSymbol = di.getUnitSymbol();
                custDim.addAttribute("", "units", "units", "", units != null ? units : "");
                if(unitSymbol != null) {
                    custDim.addAttribute("", "unitSymbol", "unitSymbol", "", unitSymbol);
                }
                element("Dimension", null, custDim);
            }
        }
    }

    protected String getZDomainRepresentation(DimensionInfo dimension, TreeSet<Double> values) {
        String elevationMetadata = null;

        final StringBuilder buff = new StringBuilder();

        if (DimensionPresentation.LIST == dimension.getPresentation()) {
            for (Double val : values) {
                buff.append(val);
                buff.append(",");
            }
            elevationMetadata = buff.substring(0, buff.length() - 1).toString().replaceAll("\\[",
                    "").replaceAll("\\]", "").replaceAll(" ", "");
        } else if (DimensionPresentation.CONTINUOUS_INTERVAL == dimension.getPresentation()) {
            buff.append(values.first());
            buff.append("/");

            buff.append(values.last());
            buff.append("/");

            Double resolution = values.last() - values.first();
            buff.append(resolution);

            elevationMetadata = buff.toString();
        } else if (DimensionPresentation.DISCRETE_INTERVAL == dimension.getPresentation()) {
            buff.append(values.first());
            buff.append("/");

            buff.append(values.last());
            buff.append("/");

            BigDecimal resolution = dimension.getResolution();
            if (resolution != null) {
                buff.append(resolution.doubleValue());
            } else {
                if (values.size() >= 2) {
                    int count = 2, i = 2;
                    Double[] zPositions = new Double[count];
                    for (Double val : values) {
                        zPositions[count - i--] = val;
                        if (i == 0)
                            break;
                    }
                    double span = zPositions[count - 1] - zPositions[count - 2];
                    buff.append(span);
                } else {
                    buff.append(0.0);
                }
            }

            elevationMetadata = buff.toString();
        }

        return elevationMetadata;
    }

    /**
     * Builds the proper presentation given the current
     * 
     * @param dimension
     * @param values
     * @return
     */
    String getTemporalDomainRepresentation(DimensionInfo dimension, TreeSet<Date> values) {
        String timeMetadata = null;

        final StringBuilder buff = new StringBuilder();
        final ISO8601Formatter df = new ISO8601Formatter();

        if (DimensionPresentation.LIST == dimension.getPresentation()) {
            for (Date date : values) {
                buff.append(df.format(date));
                buff.append(",");
            }
            timeMetadata = buff.substring(0, buff.length() - 1).toString().replaceAll("\\[", "")
                    .replaceAll("\\]", "").replaceAll(" ", "");
        } else if (DimensionPresentation.CONTINUOUS_INTERVAL == dimension.getPresentation()) {
            buff.append(df.format(((TreeSet<Date>) values).first()));
            buff.append("/");

            buff.append(df.format(((TreeSet<Date>) values).last()));
            buff.append("/");

            long durationInMilliSeconds = ((TreeSet<Date>) values).last().getTime()
                    - ((TreeSet<Date>) values).first().getTime();
            buff.append(new DefaultPeriodDuration(durationInMilliSeconds).toString());

            timeMetadata = buff.toString();
        } else if (DimensionPresentation.DISCRETE_INTERVAL == dimension.getPresentation()) {
            buff.append(df.format(((TreeSet<Date>) values).first()));
            buff.append("/");

            buff.append(df.format(((TreeSet<Date>) values).last()));
            buff.append("/");

            final BigDecimal resolution = dimension.getResolution();
            if (resolution != null) {
                buff.append(new DefaultPeriodDuration(resolution.longValue()).toString());
            } else {
                if (values.size() >= 2) {
                    int count = 2, i = 2;
                    Date[] timePositions = new Date[count];
                    for (Date date : values) {
                        timePositions[count - i--] = date;
                        if (i == 0)
                            break;
                    }
                    long durationInMilliSeconds = timePositions[count - 1].getTime()
                            - timePositions[count - 2].getTime();
                    buff.append(new DefaultPeriodDuration(durationInMilliSeconds).toString());
                } else {
                    buff.append(new DefaultPeriodDuration(0).toString());
                }
            }

            timeMetadata = buff.toString();
        }

        return timeMetadata;
    }
    
    /**
     * Builds the proper presentation given the specified value domain
     * 
     * @param dimension
     * @param values
     * @return
     */
    String getCustomDomainRepresentation(DimensionInfo dimension, TreeSet<String> values) {
        String timeMetadata = null;

        final StringBuilder buff = new StringBuilder();

        if (DimensionPresentation.LIST == dimension.getPresentation()) {
            for (String value : values) {
                buff.append(value);
                buff.append(",");
            }
            timeMetadata = buff.substring(0, buff.length() - 1).toString().replaceAll("\\[", "")
                    .replaceAll("\\]", "").replaceAll(" ", "");
        } else if (DimensionPresentation.DISCRETE_INTERVAL == dimension.getPresentation()) {
            buff.append(values.first());
            buff.append("/");

            buff.append(values.last());
            buff.append("/");

            final BigDecimal resolution = dimension.getResolution();
            if (resolution != null) {
                buff.append(resolution);
            }

            timeMetadata = buff.toString();
        }

        return timeMetadata;
    }

    /**
     * Writes out metadata for the time dimension
     * 
     * @param typeInfo
     * @throws IOException
     */
    private void handleTimeDimensionVector(FeatureTypeInfo typeInfo) throws IOException {
        // build the time dim representation
        TreeSet<Date> values = wms.getFeatureTypeTimes(typeInfo);
        String timeMetadata;
        if (values != null && !values.isEmpty()) {
            DimensionInfo timeInfo = typeInfo.getMetadata().get(ResourceInfo.TIME,
                DimensionInfo.class);
            timeMetadata = getTemporalDomainRepresentation(timeInfo, values);
        } else {
            timeMetadata = "";
        }
        writeTimeDimension(timeMetadata);
    }
    
    private void handleElevationDimensionVector(FeatureTypeInfo typeInfo) throws IOException {
        TreeSet<Double> elevations = wms.getFeatureTypeElevations(typeInfo);
        String elevationMetadata;
        DimensionInfo di = typeInfo.getMetadata().get(ResourceInfo.ELEVATION,
                DimensionInfo.class);
        String units = di.getUnits();
        String unitSymbol = di.getUnitSymbol();
        if (elevations != null && !elevations.isEmpty()) {
            elevationMetadata = getZDomainRepresentation(di, elevations);
        } else {
            elevationMetadata = "";
        }

        writeElevationDimension(elevations, elevationMetadata, units, unitSymbol);
    }

    private void writeTimeDimension(String timeMetadata) {
        AttributesImpl timeDim = new AttributesImpl();
        if (mode == Mode.WMS11) {
            timeDim.addAttribute("", "name", "name", "", "time");
            timeDim.addAttribute("", "default", "default", "", "current");
            element("Extent", timeMetadata, timeDim);
        } else {
            timeDim.addAttribute("", "name", "name", "", "time");
            timeDim.addAttribute("", "default", "default", "", "current");
            timeDim.addAttribute("", "units", "units", "", DimensionInfo.TIME_UNITS);
            element("Dimension", timeMetadata, timeDim);
        }
    }

    private void writeElevationDimension(TreeSet<Double> elevations, final String elevationMetadata, 
            final String units, final String unitSymbol) {
        Double defaultValue = elevations == null || elevations.isEmpty() ? 0 : elevations.first();
        if (mode == Mode.WMS11) {
            AttributesImpl elevDim = new AttributesImpl();
            elevDim.addAttribute("", "name", "name", "", "elevation");
            elevDim.addAttribute("", "default", "default", "", Double.toString(defaultValue));
            element("Extent", elevationMetadata, elevDim);
        } else {
            writeElevationDimensionElement(elevations, elevationMetadata, 
                    defaultValue, units, unitSymbol);
        }
    }
    
    private void writeElevationDimensionElement(TreeSet<Double> elevations, final String elevationMetadata, 
            final Double defaultValue, final String units, final String unitSymbol) {
        AttributesImpl elevDim = new AttributesImpl();
        String unitsNotNull = units;
        String unitSymNotNull = (unitSymbol == null) ? "" : unitSymbol;
        if (units == null) {
            unitsNotNull = DimensionInfo.ELEVATION_UNITS;
            unitSymNotNull = DimensionInfo.ELEVATION_UNIT_SYMBOL;
        }
        elevDim.addAttribute("", "name", "name", "", "elevation");
        if (defaultValue != null) {
            elevDim.addAttribute("", "default", "default", "", Double.toString(defaultValue));
        }
        elevDim.addAttribute("", "units", "units", "", unitsNotNull);
        if (!"".equals(unitsNotNull) && !"".equals(unitSymNotNull)) {
            elevDim.addAttribute("", "unitSymbol", "unitSymbol", "", unitSymNotNull);
        }
        element("Dimension", elevationMetadata, elevDim);
    }
    
    private void writeCustomDimension(String name, String metadata, String defaultValue, String unit, String unitSymbol) {
        AttributesImpl dim = new AttributesImpl();
        dim.addAttribute("", "name", "name", "", name);
        if (mode == Mode.WMS11) {
            if (defaultValue != null) {
                dim.addAttribute("", "default", "default", "", defaultValue);
            }
            element("Extent", metadata, dim);
        } else {
            if (defaultValue != null) {
                dim.addAttribute("", "default", "default", "", defaultValue);
            }
            dim.addAttribute("", "units", "units", "", unit != null ? unit : "");
            if (unitSymbol != null && !"".equals(unitSymbol)) {
                dim.addAttribute("", "unitSymbol", "unitSymbol", "", unitSymbol);
            }
            
            element("Dimension", metadata, dim);
        }
    }

    static class ISO8601Formatter {

        private final GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
        
        private void pad(StringBuilder buf, int value, int amt) {
            if (amt == 2 && value < 10) {
                buf.append('0');
            } else if (amt == 4 && value < 1000) {
                if (value >= 100) {
                    buf.append("0");
                } else if (value >= 10) {
                    buf.append("00");
                } else {
                    buf.append("000");
                }
            } else if (amt == 3 && value < 100) {
                if (value >= 10) {
                    buf.append('0');
                } else {
                    buf.append("00");
                }
            }
            buf.append(value);
        }
        
        public String format(Date date) {
            return format(date, new StringBuilder()).toString();
        }

        public StringBuilder format(Date date, StringBuilder buf) {
            cal.setTime(date);
            int year = cal.get(Calendar.YEAR);
            if (cal.get(Calendar.ERA) == GregorianCalendar.BC) {
                if (year > 1) {
                    buf.append('-');
                }
                year = year - 1;
            }
            pad(buf, year, 4);
            buf.append('-');
            pad(buf, cal.get(Calendar.MONTH) + 1, 2);
            buf.append('-');
            pad(buf, cal.get(Calendar.DAY_OF_MONTH), 2);
            buf.append('T');
            pad(buf, cal.get(Calendar.HOUR_OF_DAY), 2);
            buf.append(':');
            pad(buf, cal.get(Calendar.MINUTE), 2);
            buf.append(':');
            pad(buf, cal.get(Calendar.SECOND), 2);
            buf.append('.');
            pad(buf, cal.get(Calendar.MILLISECOND), 3);
            buf.append('Z');
            
            return buf;
        }
        
    }
}
