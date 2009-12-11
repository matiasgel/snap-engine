/*
 * $Id: Product.java,v 1.3 2006/12/08 13:48:36 marcop Exp $
 *
 * Copyright (C) 2002 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.framework.datamodel;

import com.bc.ceres.core.Assert;
import com.bc.ceres.core.ProgressMonitor;
import com.bc.jexp.Namespace;
import com.bc.jexp.ParseException;
import com.bc.jexp.Parser;
import com.bc.jexp.Symbol;
import com.bc.jexp.Term;
import com.bc.jexp.WritableNamespace;
import com.bc.jexp.impl.ParserImpl;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.Point;
import org.esa.beam.framework.dataio.ProductFlipper;
import org.esa.beam.framework.dataio.ProductProjectionBuilder;
import org.esa.beam.framework.dataio.ProductReader;
import org.esa.beam.framework.dataio.ProductSubsetBuilder;
import org.esa.beam.framework.dataio.ProductSubsetDef;
import org.esa.beam.framework.dataio.ProductWriter;
import org.esa.beam.framework.dataop.barithm.BandArithmetic;
import org.esa.beam.framework.dataop.barithm.RasterDataEvalEnv;
import org.esa.beam.framework.dataop.barithm.RasterDataLoop;
import org.esa.beam.framework.dataop.barithm.RasterDataSymbol;
import org.esa.beam.framework.dataop.barithm.SingleFlagSymbol;
import org.esa.beam.framework.dataop.maptransf.MapInfo;
import org.esa.beam.framework.dataop.maptransf.MapProjection;
import org.esa.beam.framework.dataop.maptransf.MapTransform;
import org.esa.beam.util.BitRaster;
import org.esa.beam.util.Debug;
import org.esa.beam.util.Guardian;
import org.esa.beam.util.ObjectUtils;
import org.esa.beam.util.StopWatch;
import org.esa.beam.util.StringUtils;
import org.esa.beam.util.math.MathUtils;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.geometry.coordinate.Polygon;

import java.awt.Dimension;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * <code>Product</code> instances are an in-memory representation of a remote sensing data product. The product is more
 * an abstract hull containing references to the data of the product or readers to retrieve the data on demant. The
 * product itself does not hold the remote sensing data. Data products can contain multiple geophysical parameters
 * stored as bands and can also have multiple metadata attributes. Also, a <code>Product</code> can contain any number
 * of <code>TiePointGrids</code> holding the tie point data.
 * <p/>
 * <p>Every product can also have a product reader and writer assigned to it. The reader represents the data source from
 * which a product was created, whereas the writer represents the data sink. Both, the source and the sink must not
 * necessarily store data in the same format. Furthermore, it is not mandatory for a product to have both of them.
 *
 * @author Norman Fomferra
 * @version $Revision$ $Date$
 */
public class Product extends ProductNode {

    public static final String METADATA_ROOT_NAME = "metadata";
    public static final String HISTORY_ROOT_NAME = "history";

    public static final String PROPERTY_NAME_FILE_LOCATION = "fileLocation";
    public static final String PROPERTY_NAME_GEOCODING = "geoCoding";
    public static final String PROPERTY_NAME_POINTING = "pointing";
    public static final String PROPERTY_NAME_PRODUCT_TYPE = "productType";

    public static final String PIN_FEATURE_TYPE_NAME = "Pin";
    public static final String GCP_FEATURE_TYPE_NAME = "GCP";

    /**
     * The location file of this product.
     */
    private File fileLocation;

    /**
     * The reader for this product. Once the reader is set, and can never be changed again.
     */
    private ProductReader reader;

    /**
     * The writer for this product. The writer is an exchangeable property of a product.
     */
    private ProductWriter writer;

    /**
     * The geo-coding of this product, if any.
     */
    private GeoCoding geoCoding;

    /**
     * The list of product listeners.
     */
    private List<ProductNodeListener> listeners;

    /**
     * This product's type ID.
     */
    private String productType;

    /**
     * The scene width of the product
     */
    private final int sceneRasterWidth;

    /**
     * The scene height of the product
     */
    private final int sceneRasterHeight;

    /**
     * The start time of the first raster line.
     */
    private ProductData.UTC startTime;

    /**
     * The start time of the first raster line.
     */
    private ProductData.UTC endTime;

    private final MetadataElement metadataRoot;
    private final ProductNodeGroup<Band> bandGroup;
    private final ProductNodeGroup<TiePointGrid> tiePointGridGroup;
    private final ProductNodeGroup<VectorDataNode> vectorDataGroup;
    private final ProductNodeGroup<FlagCoding> flagCodingGroup;
    private final ProductNodeGroup<IndexCoding> indexCodingGroup;
    private final ProductNodeGroup<Mask> maskGroup;
    private final ProductNodeGroup<Pin> pinGroup;
    private final ProductNodeGroup<Pin> gcpGroup;

    /**
     * The internal reference number of this product
     */
    private int refNo;

    /**
     * The internal reference string of this product
     */
    private String refStr;

    // todo - rename or change to "ProductContext" (nf - 01.2009)
    private ProductManager productManager;

    private PointingFactory pointingFactory;

    private String quicklookBandName;

    private Dimension preferredTileSize;


    /**
     * Creates a new product without any reader (in-memory product)
     *
     * @param name              the product name
     * @param type              the product type
     * @param sceneRasterWidth  the scene width in pixels for this data product
     * @param sceneRasterHeight the scene height in pixels for this data product
     */
    public Product(final String name, final String type, final int sceneRasterWidth, final int sceneRasterHeight) {
        this(name, type, sceneRasterWidth, sceneRasterHeight, null);
    }

    /**
     * Constructs a new product with the given name and the given reader.
     *
     * @param name              the product identifier
     * @param type              the product type
     * @param sceneRasterWidth  the scene width in pixels for this data product
     * @param sceneRasterHeight the scene height in pixels for this data product
     * @param reader            the reader used to create this product and read data from it.
     * @see ProductReader
     */
    public Product(final String name, final String type, final int sceneRasterWidth, final int sceneRasterHeight,
                   final ProductReader reader) {
        this(null, name, type, sceneRasterWidth, sceneRasterHeight, reader);
    }

    /*
     * Internally used constructor. Is kept private to keep product name and file location consistent.
     */

    private Product(final File fileLocation,
                    final String name,
                    final String type,
                    final int sceneRasterWidth,
                    final int sceneRasterHeight,
                    final ProductReader reader) {
        super(name);
        Guardian.assertNotNullOrEmpty("type", type);
        this.fileLocation = fileLocation;
        this.productType = type;
        this.reader = reader;
        this.sceneRasterWidth = sceneRasterWidth;
        this.sceneRasterHeight = sceneRasterHeight;
        this.metadataRoot = new MetadataElement(METADATA_ROOT_NAME);
        this.metadataRoot.setOwner(this);

        this.bandGroup = new ProductNodeGroup<Band>(this, "bandGroup", true);
        this.tiePointGridGroup = new ProductNodeGroup<TiePointGrid>(this, "tiePointGridGroup", true);
        this.bitmaskDefGroup = new ProductNodeGroup<BitmaskDef>(this, "bitmaskDefGroup", true);
        this.vectorDataGroup = new ProductNodeGroup<VectorDataNode>(this, "vectorDataGroup", true) {
            @Override
            public boolean add(VectorDataNode vectorDataNode) {
                final boolean added = super.add(vectorDataNode);
                if (added) {
                    getMaskGroup().add(createMask(vectorDataNode));
                }
                return added;
            }

            @Override
            public void add(int index, VectorDataNode vectorDataNode) {
                super.add(index, vectorDataNode);
                getMaskGroup().add(createMask(vectorDataNode));
            }

            @Override
            public boolean remove(VectorDataNode vectorDataNode) {
                final boolean removed = super.remove(vectorDataNode);
                if (removed) {
                    final Mask[] masks = getMaskGroup().toArray(new Mask[getMaskGroup().getNodeCount()]);
                    for (final Mask mask : masks) {
                        if (mask.getImageType() instanceof Mask.VectorDataType) {
                            if (Mask.VectorDataType.getVectorData(mask) == vectorDataNode) {
                                getMaskGroup().remove(mask);
                                break;
                            }
                        }
                    }
                }
                return removed;
            }

            private Mask createMask(VectorDataNode vectorDataNode) {
                final Mask mask = new Mask(vectorDataNode.getName(),
                                           getSceneRasterWidth(),
                                           getSceneRasterHeight(),
                                           new Mask.VectorDataType());
                Mask.VectorDataType.setVectorData(mask, vectorDataNode);
                return mask;
            }
        };
        this.indexCodingGroup = new ProductNodeGroup<IndexCoding>(this, "indexCodingGroup", true);
        this.flagCodingGroup = new ProductNodeGroup<FlagCoding>(this, "flagCodingGroup", true);
        this.maskGroup = new ProductNodeGroup<Mask>(this, "maskGroup", true);
        this.pinGroup = new ProductNodeGroup<Pin>(this, "pinGroup", true) {
            @Override
            public boolean add(Pin pin) {
                final boolean added = super.add(pin);
                if (added) {
                    addToVectorData(pin);
                }
                return added;
            }

            @Override
            public void add(int index, Pin pin) {
                super.add(index, pin);
                addToVectorData(pin);
            }

            @Override
            public boolean remove(Pin pin) {
                final boolean removed = super.remove(pin);
                if (removed) {
                    removeFromVectorData(pin);
                }
                return removed;
            }

            private void addToVectorData(final Pin pin) {
                vectorDataGroup.get("pins").getFeatureCollection().add(pin.getFeature());
            }

            private void removeFromVectorData(Pin pin) {
                final Iterator<SimpleFeature> iterator = vectorDataGroup.get("pins").getFeatureCollection().iterator();
                while (iterator.hasNext()) {
                    final SimpleFeature feature = iterator.next();
                    if (feature == pin.getFeature()) {
                        iterator.remove();
                        break;
                    }
                }
            }
        };

        this.gcpGroup = new ProductNodeGroup<Pin>(this, "gcpGroup", true);

        this.vectorDataGroup.add(new VectorDataNode("pins", Pin.getPinFeatureType()));
        this.vectorDataGroup.add(new VectorDataNode("ground_control_points", createPlacemarkFeatureType(GCP_FEATURE_TYPE_NAME, "geoPoint")));

        setModified(false);

        addProductNodeListener(new ProductNodeListenerAdapter() {
            @Override
            public void nodeChanged(final ProductNodeEvent event) {
                if (ProductNode.PROPERTY_NAME_NAME.equals(event.getPropertyName())) {
                    handleNameChange(event);
                } else if (PROPERTY_NAME_GEOCODING.equals(event.getPropertyName())) {
                    handleGeoCodingChange();
                }
            }
        });
    }

    private static VectorDataNode createTestShapes1(String name) {
        SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
        DefaultGeographicCRS crs = DefaultGeographicCRS.WGS84;
        ftb.setCRS(crs);
        ftb.setName(name);
        ftb.add("geometry", Polygon.class);
        ftb.setDefaultGeometry("geometry");
        SimpleFeatureType ft = ftb.buildFeatureType();

        GeometryFactory gf = new GeometryFactory();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(ft);
        DefaultFeatureCollection fc = new DefaultFeatureCollection(name, ft);
        for (int i = 0; i < 10; i++) {
            double x = i * 100;
            double y = i * 100;
            SimpleFeature f = fb.buildFeature(name + "_" + i, new Object[]{
                    gf.createPolygon(gf.createLinearRing(new Coordinate[]{
                            new Coordinate(x, y),
                            new Coordinate(x + 100, y),
                            new Coordinate(x + 100, y + 100),
                            new Coordinate(x, y + 100),
                            new Coordinate(x, y),
                    }), null)
            });
            fc.add(f);
        }

        return new VectorDataNode(name, fc);
    }

    private static VectorDataNode createTestShapes2(String name) {
        SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
        DefaultGeographicCRS crs = DefaultGeographicCRS.WGS84;
        ftb.setCRS(crs);
        ftb.setName(name);
        ftb.add("geometry", LineString.class);
        ftb.setDefaultGeometry("geometry");
        SimpleFeatureType ft = ftb.buildFeatureType();

        GeometryFactory gf = new GeometryFactory();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(ft);
        DefaultFeatureCollection fc = new DefaultFeatureCollection(name, ft);
        for (int i = 0; i < 10; i++) {
            double x = i * 100;
            double y = i * 100;
            SimpleFeature f = fb.buildFeature(name + "_" + i, new Object[]{
                    gf.createLineString(new Coordinate[]{
                            new Coordinate(x, y),
                            new Coordinate(x + 50, y + 50),
                            new Coordinate(x + 100, y + 50),
                            new Coordinate(x + 150, y + 100),
                            new Coordinate(x + 200, y + 100),
                    })
            });
            fc.add(f);
        }

        return new VectorDataNode(name, fc);
    }

    private static SimpleFeatureType createPlacemarkFeatureType(String typeName, String defaultGeometryName) {
        SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
        DefaultGeographicCRS crs = DefaultGeographicCRS.WGS84;
        ftb.setCRS(crs);
        ftb.setName(typeName);
        ftb.add("label", String.class);
        ftb.add("geoPoint", Point.class, crs);
        ftb.add("pixelPoint", Point.class, crs);
        ftb.setDefaultGeometry(defaultGeometryName);
        return ftb.buildFeatureType();
    }

    private void handleGeoCodingChange() {
        final ProductNodeGroup<Pin> pinGroup = getPinGroup();
        for (int i = 0; i < pinGroup.getNodeCount(); i++) {
            final Pin pin = pinGroup.get(i);
            final PinDescriptor pinDescriptor = PinDescriptor.INSTANCE;
            final GeoPos geoPos = pin.getGeoPos();
            final PixelPos pixelPos = pin.getPixelPos();
            if (pixelPos != null) {
                pinDescriptor.updateGeoPos(getGeoCoding(), pixelPos, geoPos);
            }
            pin.setGeoPos(geoPos);
        }
    }

    private void handleNameChange(final ProductNodeEvent event) {
        final String oldName = (String) event.getOldValue();
        final String newName = event.getSourceNode().getName();

        final String oldExternName = BandArithmetic.createExternalName(oldName);
        final String newExternName = BandArithmetic.createExternalName(newName);

        final ProductVisitorAdapter productVisitorAdapter = new ProductVisitorAdapter() {
            @Override
            public void visit(Product product) {
                if (product == event.getSourceNode()) {
                    product.setFileLocation(null);
                }
            }

            @Override
            public void visit(TiePointGrid grid) {
                grid.updateExpression(oldExternName, newExternName);
            }

            @Override
            public void visit(Band band) {
                band.updateExpression(oldExternName, newExternName);
            }

            @Override
            public void visit(Mask mask) {
                mask.updateExpression(oldExternName, newExternName);
            }

            @Override
            public void visit(VirtualBand virtualBand) {
                virtualBand.updateExpression(oldExternName, newExternName);
            }

            @Override
            public void visit(BitmaskDef bitmaskDef) {
                bitmaskDef.updateExpression(oldExternName, newExternName);
            }

            @Override
            public void visit(ProductNodeGroup group) {
                group.updateExpression(oldExternName, newExternName);
            }
        };
        acceptVisitor(productVisitorAdapter);
    }

    /**
     * Retrieves the disk location of this product. The return value can be <code>null</code> when the product has no
     * disk location (pure virtual memory product)
     *
     * @return the file location, may be <code>null</code>
     */
    public File getFileLocation() {
        return fileLocation;
    }

    /**
     * Sets the file location for this product.
     *
     * @param fileLocation the file location, may be <code>null</code>
     */
    public void setFileLocation(final File fileLocation) {
        this.fileLocation = fileLocation;
    }


    /**
     * Overwrites the{@link ProductNode#setOwner(ProductNode)} method in order to
     * throw an <code>IllegalStateException</code>,
     * since products currently cannot have an owner.
     */
    @Override
    protected void setOwner(final ProductNode owner) {
        throw new IllegalStateException("a product can not have an owner");
    }

    //////////////////////////////////////////////////////////////////////////
    // Attribute Query


    /**
     * Gets the product type string.
     *
     * @return the product type string
     */
    public String getProductType() {
        return productType;
    }

    /**
     * Sets the product type of this product.
     *
     * @param productType the product type.
     */
    public void setProductType(final String productType) {
        Guardian.assertNotNullOrEmpty("productType", productType);
        if (!ObjectUtils.equalObjects(this.productType, productType)) {
            final String oldType = this.productType;
            this.productType = productType;
            fireProductNodeChanged(PROPERTY_NAME_PRODUCT_TYPE, oldType);
            setModified(true);
        }
    }

    /**
     * Sets the product reader which will be used to create this product in-memory represention from an external source
     * and which will be used to (re-)load band rasters.
     *
     * @param reader the product reader.
     * @throws IllegalArgumentException if the given reader is null.
     */
    public void setProductReader(final ProductReader reader) {
        Guardian.assertNotNull("ProductReader", reader);
        this.reader = reader;
    }

    /**
     * Returns the reader which was used to create this product in-memory represention from an external source and which
     * will be used to (re-)load band rasters.
     *
     * @return the product reader, can be <code>null</code>
     */
    @Override
    public ProductReader getProductReader() {
        return reader;
    }

    /**
     * Sets the writer which will be used to write modifications of this product's in-memory represention to an external
     * destination.
     *
     * @param writer the product writer, can be <code>null</code>
     */
    public void setProductWriter(final ProductWriter writer) {
        this.writer = writer;
    }

    /**
     * Returns the writer which will be used to write modifications of this product's in-memory represention to an
     * external destination.
     *
     * @return the product writer, can be <code>null</code>
     */
    @Override
    public ProductWriter getProductWriter() {
        return writer;
    }

    /**
     * Closes and clears this product's reader (if any).
     *
     * @throws IOException if an I/O error occurs
     * @see #closeIO
     */
    public void closeProductReader() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    /**
     * Closes and clears this product's writer (if any).
     *
     * @throws IOException if an I/O error occurs
     * @see #closeIO
     */
    public void closeProductWriter() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    /**
     * Closes the file I/O for this product. Calls in sequence <code>{@link #closeProductReader}</code>  and
     * <code>{@link #closeProductWriter}</code>. The <code>{@link #dispose}</code> method is <b>not</b> called, but
     * should be called if the product instance is no longer in use.
     *
     * @throws IOException if an I/O error occurs
     * @see #closeProductReader
     * @see #closeProductWriter
     * @see #dispose
     */
    public void closeIO() throws IOException {
        IOException eI = null;
        try {
            closeProductReader();
        } catch (IOException e) {
            eI = e;
        }
        IOException eO = null;
        try {
            closeProductWriter();
        } catch (IOException e) {
            eO = e;
        }
        if (eI != null) {
            throw eI;
        }
        if (eO != null) {
            throw eO;
        }
    }

    /**
     * Releases all of the resources used by this object instance and all of its owned children. Its primary use is to
     * allow the garbage collector to perform a vanilla job.
     * <p/>
     * <p>This method should be called only if it is for sure that this object instance will never be used again. The
     * results of referencing an instance of this class after a call to <code>dispose()</code> are undefined.
     * </p>
     * <p>Overrides of this method should always call <code>super.dispose();</code> after disposing this instance.
     * </p>
     * <p>This implementation also calls the <code>closeIO</code> in order to release all open I/O resources.
     */
    @Override
    public void dispose() {
        try {
            closeIO();
        } catch (IOException ignore) {
            // ignore
        }

        reader = null;
        writer = null;

        metadataRoot.dispose();
        bandGroup.dispose();
        tiePointGridGroup.dispose();
        bitmaskDefGroup.dispose();
        flagCodingGroup.dispose();
        indexCodingGroup.dispose();
        maskGroup.dispose();
        vectorDataGroup.dispose();
        pinGroup.dispose();
        gcpGroup.dispose();

        pointingFactory = null;
        productManager = null;

        if (geoCoding != null) {
            geoCoding.dispose();
            geoCoding = null;
        }

        if (validMasks != null) {
            validMasks.clear();
            validMasks = null;
        }

        if (listeners != null) {
            listeners.clear();
            listeners = null;
        }

        fileLocation = null;
    }

    /**
     * Gets the pointing factory associated with this data product.
     *
     * @return the pointing factory or null, if none
     */
    public PointingFactory getPointingFactory() {
        return pointingFactory;
    }

    /**
     * Sets the pointing factory for this data product.
     *
     * @param pointingFactory the pointing factory
     */
    public void setPointingFactory(PointingFactory pointingFactory) {
        this.pointingFactory = pointingFactory;
    }

    /**
     * Geo-codes this data product.
     *
     * @param geoCoding the geo-coding, if <code>null</code> geo-coding is removed
     * @throws IllegalArgumentException <br>- if the given <code>GeoCoding</code> is a <code>TiePointGeoCoding</code>
     *                                  and <code>latGrid</code> or <code>lonGrid</code> are not instances of tie point
     *                                  grids in this product. <br>- if the given <code>GeoCoding</code> is a
     *                                  <code>MapGeoCoding</code> and its <code>MapInfo</code> is <code>null</code>
     *                                  <br>- if the given <code>GeoCoding</code> is a <code>MapGeoCoding</code> and the
     *                                  <code>sceneWith</code> or <code>sceneHeight</code> of its <code>MapInfo</code>
     *                                  is not equal to this products <code>sceneRasterWidth</code> or
     *                                  <code>sceneRasterHeight</code>
     */
    public void setGeoCoding(final GeoCoding geoCoding) {
        checkGeoCoding(geoCoding);
        if (!ObjectUtils.equalObjects(this.geoCoding, geoCoding)) {
            this.geoCoding = geoCoding;
            fireProductNodeChanged(PROPERTY_NAME_GEOCODING);
            setModified(true);
        }
    }

    /**
     * Returns the geo-coding used for this data product.
     *
     * @return the geo-coding, can be <code>null</code> if this product is not geo-coded.
     */
    public GeoCoding getGeoCoding() {
        return geoCoding;
    }

    /**
     * Tests if all bands of this product are using a single, uniform geo-coding. Uniformity is tested by comparing
     * the band's geo-coding against the geo-coding of this product using the {@link Object#equals(Object)} method.
     * If this product does not have a geo-coding, the method returns false.
     *
     * @return true, if so
     */
    public boolean isUsingSingleGeoCoding() {
        final GeoCoding geoCoding = getGeoCoding();
        if (geoCoding == null) {
            return false;
        }

        for (int i = 0; i < getNumBands(); i++) {
            if (!geoCoding.equals(getBandAt(i).getGeoCoding())) {
                return false;
            }
        }

        for (int i = 0; i < getNumTiePointGrids(); i++) {
            if (!geoCoding.equals(getTiePointGridAt(i).getGeoCoding())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Transfers the geo-coding of this product instance to the {@link Product destProduct} with respect to
     * the given {@link ProductSubsetDef subsetDef}.
     *
     * @param destProduct the destination product
     * @param subsetDef   the definition of the subset, may be <code>null</code>
     * @return true, if the geo-coding could be transferred.
     */
    public boolean transferGeoCodingTo(final Product destProduct, final ProductSubsetDef subsetDef) {
        final Scene srcScene = SceneFactory.createScene(this);
        if (srcScene == null) {
            return false;
        }
        final Scene destScene = SceneFactory.createScene(destProduct);
        if (destScene == null) {
            return false;
        }
        return srcScene.transferGeoCodingTo(destScene, subsetDef);
    }

    /**
     * Returns the scene width in pixels for this data product.
     *
     * @return the scene width in pixels for this data product.
     */
    public int getSceneRasterWidth() {
        return sceneRasterWidth;
    }

    /**
     * Returns the scene height in pixels for this data product.
     *
     * @return the scene height in pixels for this data product.
     */
    public int getSceneRasterHeight() {
        return sceneRasterHeight;
    }

    /**
     * Gets the (sensing) start time associated with the first raster data line.
     * <p/>
     * <p>For Level-1/2 products this is
     * the data-take time associated with the first raster data line.
     * For Level-3 products, this could be the start time of first input product
     * contributing data.</p>
     *
     * @return the sensing start time, can be null e.g. for non-swath products
     */
    public ProductData.UTC getStartTime() {
        return startTime;
    }

    /**
     * Sets the (sensing) start time of this product.
     * <p/>
     * <p>For Level-1/2 products this is
     * the data-take time associated with the first raster data line.
     * For Level-3 products, this could be the start time of first input product
     * contributing data.</p>
     *
     * @param startTime the sensing start time, can be null
     */
    public void setStartTime(final ProductData.UTC startTime) {
        this.startTime = startTime;
    }

    /**
     * Gets the (sensing) stop time associated with the last raster data line.
     * <p/>
     * <p>For Level-1/2 products this is
     * the data-take time associated with the last raster data line.
     * For Level-3 products, this could be the end time of last input product
     * contributing data.</p>
     *
     * @return the stop time , can be null e.g. for non-swath products
     */
    public ProductData.UTC getEndTime() {
        return endTime;
    }

    /**
     * Sets the (sensing) stop time associated with the first raster data line.
     * <p/>
     * <p>For Level-1/2 products this is
     * the data-take time associated with the last raster data line.
     * For Level-3 products, this could be the end time of last input product
     * contributing data.</p>
     *
     * @param endTime the sensing stop time, can be null
     */
    public void setEndTime(final ProductData.UTC endTime) {
        this.endTime = endTime;
    }

    /**
     * Gets the root element of the associated metadata.
     *
     * @return the metadata root element
     */
    public MetadataElement getMetadataRoot() {
        return metadataRoot;
    }

    //////////////////////////////////////////////////////////////////////////
    // Tie-point grid support

    /**
     * Adds the given tie-point grid to this product.
     *
     * @param tiePointGrid the tie-point grid to added, ignored if <code>null</code>
     */
    public void addTiePointGrid(final TiePointGrid tiePointGrid) {
        if (containsRasterDataNode(tiePointGrid.getName())) {
            throw new IllegalArgumentException("The Product '" + getName() + "' already contains " +
                    "a tie-point grid with the name '" + tiePointGrid.getName() + "'.");
        }
        tiePointGridGroup.add(tiePointGrid);
    }

    /**
     * Removes the tie-point grid from this product.
     *
     * @param tiePointGrid the tie-point grid to be removed, ignored if <code>null</code>
     * @return <code>true</code> if node could be removed
     */
    public boolean removeTiePointGrid(final TiePointGrid tiePointGrid) {
        return tiePointGridGroup.remove(tiePointGrid);
    }

    /**
     * Returns the number of tie-point grids contained in this product
     *
     * @return the number of tie-point grids
     */
    public int getNumTiePointGrids() {
        return tiePointGridGroup.getNodeCount();
    }

    /**
     * Returns the tie-point grid at the given index.
     *
     * @param index the tie-point grid index
     * @return the tie-point grid at the given index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     */
    public TiePointGrid getTiePointGridAt(final int index) {
        return tiePointGridGroup.get(index);
    }

    /**
     * Returns a string array containing the names of the tie-point grids contained in this product
     *
     * @return a string array containing the names of the tie-point grids contained in this product. If this product has
     *         no tie-point grids a zero-length-array is returned.
     */
    public String[] getTiePointGridNames() {
        return tiePointGridGroup.getNodeNames();
    }

    /**
     * Returns an array of tie-point grids contained in this product
     *
     * @return an array of tie-point grids contained in this product. If this product has no  tie-point grids a
     *         zero-length-array is returned.
     */
    public TiePointGrid[] getTiePointGrids() {
        final TiePointGrid[] tiePointGrids = new TiePointGrid[getNumTiePointGrids()];
        for (int i = 0; i < tiePointGrids.length; i++) {
            tiePointGrids[i] = getTiePointGridAt(i);
        }
        return tiePointGrids;
    }

    /**
     * Returns the tie-point grid with the given name.
     *
     * @param name the tie-point grid name
     * @return the tie-point grid with the given name or <code>null</code> if a tie-point grid with the given name is
     *         not contained in this product.
     */
    public TiePointGrid getTiePointGrid(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return tiePointGridGroup.get(name);
    }

    /**
     * Returns the index for the tie-point grid with the given name.
     *
     * @param name the tie-point grid name
     * @return the tie-point grid index or <code>-1</code> if a tie-point grid with the given name is not contained in
     *         this product.
     * @throws IllegalArgumentException if the given name is <code>null</code> or empty.
     */
    public int getTiePointGridIndex(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return tiePointGridGroup.indexOf(name);
    }

    /**
     * Tests if a tie-point grid with the given name is contained in this product.
     *
     * @param name the name, must not be <code>null</code>
     * @return <code>true</code> if a tie-point grid with the given name is contained in this product,
     *         <code>false</code> otherwise
     */
    public boolean containsTiePointGrid(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return tiePointGridGroup.contains(name);
    }

    //////////////////////////////////////////////////////////////////////////
    // Band support

    /**
     * Adds the given band to this product.
     *
     * @param band the band to added, must not be <code>null</code>
     */
    public void addBand(final Band band) {
        Guardian.assertNotNull("band", band);
        if (band.getSceneRasterWidth() != getSceneRasterWidth()
                || band.getSceneRasterHeight() != getSceneRasterHeight()) {
            throw new IllegalArgumentException("illegal raster dimensions");
        }
        if (containsRasterDataNode(band.getName())) {
            throw new IllegalArgumentException("The Product '" + getName() + "' already contains " +
                    "a band with the name '" + band.getName() + "'.");
        }
        bandGroup.add(band);
    }

    /**
     * Creates a new band with the given name and data type and adds it to this product and returns it.
     *
     * @param bandName the new band's name
     * @param dataType the raster data type, must be one of the multiple <code>ProductData.TYPE_<i>X</i></code>
     *                 constants
     * @return the new band which has just been added
     */
    public Band addBand(final String bandName, final int dataType) {
        final Band band = new Band(bandName, dataType, getSceneRasterWidth(), getSceneRasterHeight());
        addBand(band);
        return band;
    }

    /**
     * Removes the given band from this product.
     *
     * @param band the band to be removed, ignored if <code>null</code>
     * @return {@code true} if removed succesfully, otherwise {@code false}
     */
    public boolean removeBand(final Band band) {
        return bandGroup.remove(band);
    }

    /**
     * @return the number of bands contained in this product.
     */
    public int getNumBands() {
        return bandGroup.getNodeCount();
    }

    /**
     * Returns the band at the given index.
     *
     * @param index the band index
     * @return the band at the given index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     */
    public Band getBandAt(final int index) {
        return bandGroup.get(index);
    }

    /**
     * Returns a string array containing the names of the bands contained in this product
     *
     * @return a string array containing the names of the bands contained in this product. If this product has no bands
     *         a zero-length-array is returned.
     */
    public String[] getBandNames() {
        return bandGroup.getNodeNames();
    }

    /**
     * Returns an array of bands contained in this product
     *
     * @return an array of bands contained in this product. If this product has no bands a zero-length-array is
     *         returned.
     */
    public Band[] getBands() {
        return bandGroup.toArray(new Band[getNumBands()]);
    }


    /**
     * Returns the band with the given name.
     *
     * @param name the band name
     * @return the band with the given name or <code>null</code> if a band with the given name is not contained in this
     *         product.
     * @throws IllegalArgumentException if the given name is <code>null</code> or empty.
     */
    public Band getBand(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return bandGroup.get(name);
    }

    /**
     * Returns the index for the band with the given name.
     *
     * @param name the band name
     * @return the band index or <code>-1</code> if a band with the given name is not contained in this product.
     * @throws IllegalArgumentException if the given name is <code>null</code> or empty.
     */
    public int getBandIndex(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return bandGroup.indexOf(name);
    }

    /**
     * Tests if a band with the given name is contained in this product.
     *
     * @param name the name, must not be <code>null</code>
     * @return <code>true</code> if a band with the given name is contained in this product, <code>false</code>
     *         otherwise
     * @throws IllegalArgumentException if the given name is <code>null</code> or empty.
     */
    public boolean containsBand(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return bandGroup.contains(name);
    }

    //////////////////////////////////////////////////////////////////////////
    // Raster data node  support

    /**
     * Tests if a raster data node with the given name is contained in this product. Raster data nodes can be bands or
     * tie-point grids.
     *
     * @param name the name, must not be <code>null</code>
     * @return <code>true</code> if a raster data node with the given name is contained in this product,
     *         <code>false</code> otherwise
     */
    public boolean containsRasterDataNode(final String name) {
        return containsBand(name) || containsTiePointGrid(name) || getMaskGroup().contains(name);
    }

    /**
     * Gets the raster data node with the given name. The method first searches for bands with the given name, then for
     * tie-point grids. If neither bands nor tie-point grids exist with the given name, <code>null</code> is returned.
     *
     * @param name the name, must not be <code>null</code>
     * @return the raster data node with the given name or <code>null</code> if a raster data node with the given name
     *         is not contained in this product.
     */
    public RasterDataNode getRasterDataNode(final String name) {
        RasterDataNode rasterDataNode = getBand(name);
        if (rasterDataNode != null) {
            return rasterDataNode;
        }
        rasterDataNode = getTiePointGrid(name);
        if (rasterDataNode != null) {
            return rasterDataNode;
        }
        return getMaskGroup().get(name);
    }

    //////////////////////////////////////////////////////////////////////////
    // Mask support

    public ProductNodeGroup<Mask> getMaskGroup() {
        return maskGroup;
    }

    //////////////////////////////////////////////////////////////////////////
    // Vector data support

    public ProductNodeGroup<VectorDataNode> getVectorDataGroup() {
        return vectorDataGroup;
    }

    public boolean isInternalNode(VectorDataNode vectorDataNode) {
        final SimpleFeatureType simpleFeatureType = vectorDataNode.getFeatureType();
        final String typeName = simpleFeatureType.getTypeName();
        return PIN_FEATURE_TYPE_NAME.equals(typeName) || GCP_FEATURE_TYPE_NAME.equals(typeName);
    }

    //////////////////////////////////////////////////////////////////////////
    // Sample-coding support

    public ProductNodeGroup<FlagCoding> getFlagCodingGroup() {
        return flagCodingGroup;
    }

    public ProductNodeGroup<IndexCoding> getIndexCodingGroup() {
        return indexCodingGroup;
    }

    //////////////////////////////////////////////////////////////////////////
    // Pixel Coordinate Tests

    /**
     * Tests if the given pixel position is within the product pixel bounds.
     *
     * @param x the x coordinate of the pixel position
     * @param y the y coordinate of the pixel position
     * @return true, if so
     * @see #containsPixel(PixelPos)
     */
    public boolean containsPixel(final float x, final float y) {
        return x >= 0.0f && x <= getSceneRasterWidth() &&
                y >= 0.0f && y <= getSceneRasterHeight();
    }

    /**
     * Tests if the given pixel position is within the product pixel bounds.
     *
     * @param pixelPos the pixel position, must not be null
     * @return true, if so
     * @see #containsPixel(float,float)
     */
    public boolean containsPixel(final PixelPos pixelPos) {
        return containsPixel(pixelPos.x, pixelPos.y);
    }

    //////////////////////////////////////////////////////////////////////////
    // GCP support

    /**
     * Gets the group of ground-control points (GCPs).
     *
     * @return the GCP group.
     */
    public ProductNodeGroup<Pin> getGcpGroup() {
        return gcpGroup;
    }

    //////////////////////////////////////////////////////////////////////////
    // Pin support

    /**
     * Gets the group of pins.
     *
     * @return the pin group.
     */
    public ProductNodeGroup<Pin> getPinGroup() {
        return pinGroup;
    }

    /**
     * Checks whether or not the given term is compatible with this product.
     *
     * @param term The term to examine.
     * @return <code>false</code> if the term has an expression referencing nodes which are not contained in
     *         this product, <code>true</code> otherwise.
     */
    public boolean isCompatibleTerm(final Term term) {
        final RasterDataSymbol[] refRasterDataSymbols = BandArithmetic.getRefRasterDataSymbols(term);
        final Set<String> flags = new TreeSet<String>();
        final Set<String> rasters = new TreeSet<String>();
        for (final RasterDataSymbol refRasterDataSymbol : refRasterDataSymbols) {
            rasters.add(refRasterDataSymbol.getRaster().getName());
            final String name = refRasterDataSymbol.getName();
            if (isFlagSymbol(name)) {
                final int index = name.indexOf('.');
                flags.add(name.substring(index + 1));
            }
        }
        final String[] flagNames = getAllFlagNames();
        for (String flag : flags) {
            if (!StringUtils.containsIgnoreCase(flagNames, flag)) {
                return false;
            }
        }
        final String[] rasterNames = StringUtils.addArrays(getBandNames(), getTiePointGridNames());
        for (String raster : rasters) {
            if (!StringUtils.containsIgnoreCase(rasterNames, raster)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether or not the given product is compatible with this product.
     *
     * @param product the product to compare with
     * @param eps     the maximum lat/lon error in degree
     * @return <code>false</code> if the scene dimensions or geocoding are different, <code>true</code> otherwise.
     */
    public boolean isCompatibleProduct(final Product product, final float eps) {
        Guardian.assertNotNull("product", product);
        if (this == product) {
            return true;
        }
        if (getSceneRasterWidth() != product.getSceneRasterWidth()) {
            return false;
        }
        if (getSceneRasterHeight() != product.getSceneRasterHeight()) {
            return false;
        }
        if (getGeoCoding() == null && product.getGeoCoding() != null) {
            return false;
        }
        if (getGeoCoding() != null) {
            if (product.getGeoCoding() == null) {
                return false;
            }

            final PixelPos pixelPos = new PixelPos();
            final GeoPos geoPos1 = new GeoPos();
            final GeoPos geoPos2 = new GeoPos();

            pixelPos.x = 0.5f;
            pixelPos.y = 0.5f;
            getGeoCoding().getGeoPos(pixelPos, geoPos1);
            product.getGeoCoding().getGeoPos(pixelPos, geoPos2);
            if (!equalsLatLon(geoPos1, geoPos2, eps)) {
                return false;
            }

            pixelPos.x = getSceneRasterWidth() - 1 + 0.5f;
            pixelPos.y = 0.5f;
            getGeoCoding().getGeoPos(pixelPos, geoPos1);
            product.getGeoCoding().getGeoPos(pixelPos, geoPos2);
            if (!equalsLatLon(geoPos1, geoPos2, eps)) {
                return false;
            }

            pixelPos.x = 0.5f;
            pixelPos.y = getSceneRasterHeight() - 1 + 0.5f;
            getGeoCoding().getGeoPos(pixelPos, geoPos1);
            product.getGeoCoding().getGeoPos(pixelPos, geoPos2);
            if (!equalsLatLon(geoPos1, geoPos2, eps)) {
                return false;
            }

            pixelPos.x = getSceneRasterWidth() - 1 + 0.5f;
            pixelPos.y = getSceneRasterHeight() - 1 + 0.5f;
            getGeoCoding().getGeoPos(pixelPos, geoPos1);
            product.getGeoCoding().getGeoPos(pixelPos, geoPos2);
            if (!equalsLatLon(geoPos1, geoPos2, eps)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equalsLatLon(final GeoPos pos1, final GeoPos pos2, final float eps) {
        return MathUtils.equalValues(pos1.lat, pos2.lat, eps) && MathUtils.equalValues(pos1.lon, pos2.lon, eps);
    }

    /**
     * Replaces the given old bitmask definition in this container with the given new bitmask definition. If the given
     * old bitmask definition not contained in this container, the new bitmask definition was added. Ignored if the new
     * bitmask definition is <code>null</code>.
     *
     * @param oldBitmaskDef the bitmask definition to be replaced.
     * @param newBitmaskDef the new bitmask definition.
     * @deprecated since BEAM 4.7, no replacement.
     */
    @Deprecated
    public void replaceBitmaskDef(final BitmaskDef oldBitmaskDef, final BitmaskDef newBitmaskDef) {
        if (newBitmaskDef != null) {
            final Mask oldMask;
            final int insertIndex = bitmaskDefGroup.indexOf(oldBitmaskDef);
            if (insertIndex == -1) {
                oldMask = null;
                addBitmaskDef(newBitmaskDef);
            } else {
                if (bitmaskDefGroup.remove(oldBitmaskDef)) {
                    fireNodeRemoved(oldBitmaskDef, bitmaskDefGroup);
                }
                bitmaskDefGroup.add(insertIndex, newBitmaskDef);
                fireNodeAdded(newBitmaskDef, bitmaskDefGroup);

                // also adjust mask group
                oldMask = maskGroup.get(oldBitmaskDef.getName());
                if (oldMask != null) {
                    maskGroup.remove(oldMask);
                }
                maskGroup.add(newBitmaskDef.createMask(sceneRasterWidth, insertIndex));
            }
            for (final Band band : getBands()) {
                final BitmaskOverlayInfo overlayInfo = band.getBitmaskOverlayInfo();
                if (overlayInfo != null && overlayInfo.containsBitmaskDef(oldBitmaskDef)) {
                    overlayInfo.removeBitmaskDef(oldBitmaskDef);
                    overlayInfo.addBitmaskDef(newBitmaskDef);

                    // also adjust overlay mask group
                    band.getOverlayMaskGroup().remove(oldMask);
                    band.getOverlayMaskGroup().add(maskGroup.get(newBitmaskDef.getName()));
                }
            }
            for (final TiePointGrid grid : getTiePointGrids()) {
                final BitmaskOverlayInfo overlayInfo = grid.getBitmaskOverlayInfo();
                if (overlayInfo != null && overlayInfo.containsBitmaskDef(oldBitmaskDef)) {
                    overlayInfo.removeBitmaskDef(oldBitmaskDef);
                    overlayInfo.addBitmaskDef(newBitmaskDef);

                    // also adjust overlay mask group
                    grid.getOverlayMaskGroup().remove(oldMask);
                    grid.getOverlayMaskGroup().add(maskGroup.get(newBitmaskDef.getName()));
                }
            }
        }
    }

    /**
     * Parses a mathematical expression given as a text string.
     *
     * @param expression a expression given as a text string, e.g. "radiance_4 / (1.0 + radiance_11)".
     * @return a term parsed from the given expression string
     * @throws ParseException if the expression could not successfully be parsed
     */
    public Term parseExpression(final String expression) throws ParseException {
        final Parser parser = createBandArithmeticParser();
        return parser.parse(expression);
    }

    //////////////////////////////////////////////////////////////////////////
    // Visitor-Pattern support

    /**
     * Accepts the given visitor. This method implements the well known 'Visitor' design pattern of the gang-of-four.
     * The visitor pattern allows to define new operations on the product data model without the need to add more code
     * to it. The new operation is implemented by the visitor.
     * <p/>
     * <p>The method subsequentially visits (calls <code>acceptVisitor</code> for) all bands, tie-point grids and flag
     * codings. Finally it visits product metadata root element and calls <code>visitor.visit(this)</code>.
     *
     * @param visitor the visitor, must not be <code>null</code>
     */
    @Override
    public void acceptVisitor(final ProductVisitor visitor) {
        Guardian.assertNotNull("visitor", visitor);
        bandGroup.acceptVisitor(visitor);
        tiePointGridGroup.acceptVisitor(visitor);
        flagCodingGroup.acceptVisitor(visitor);
        indexCodingGroup.acceptVisitor(visitor);
        vectorDataGroup.acceptVisitor(visitor);
        bitmaskDefGroup.acceptVisitor(visitor);
        maskGroup.acceptVisitor(visitor);
        pinGroup.acceptVisitor(visitor);
        gcpGroup.acceptVisitor(visitor);
        metadataRoot.acceptVisitor(visitor);
        visitor.visit(this);
    }

    //////////////////////////////////////////////////////////////////////////
    // Product listener support

    /**
     * Adds a <code>ProductNodeListener</code> to this product. The <code>ProductNodeListener</code> is informed each
     * time a node in this product changes.
     *
     * @param listener the listener to be added
     * @return boolean if listener was added or not
     */
    public boolean addProductNodeListener(final ProductNodeListener listener) {
        if (listener != null) {
            if (listeners == null) {
                listeners = new ArrayList<ProductNodeListener>();
            }
            if (!listeners.contains(listener)) {
                listeners.add(listener);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a <code>ProductNodeListener</code> from this product.
     *
     * @param listener the listener to be removed.
     */
    public void removeProductNodeListener(final ProductNodeListener listener) {
        if (listener != null && listeners != null) {
            listeners.remove(listener);
        }
    }

    public ProductNodeListener[] getProductNodeListeners() {
        if (listeners == null) {
            return new ProductNodeListener[0];
        }
        return listeners.toArray(new ProductNodeListener[listeners.size()]);
    }

    protected boolean hasProductNodeListeners() {
        return listeners != null && listeners.size() > 0;
    }

    protected void fireNodeChanged(ProductNode sourceNode, String propertyName) {
        fireEvent(sourceNode, propertyName, null);
    }

    protected void fireNodeChanged(ProductNode sourceNode, String propertyName, Object oldValue) {
        fireEvent(sourceNode, propertyName, oldValue);
    }

    protected void fireNodeDataChanged(DataNode sourceNode) {
        fireEvent(sourceNode, ProductNodeEvent.NODE_DATA_CHANGED, null);
    }

    protected void fireNodeAdded(ProductNode childNode, ProductNodeGroup nodeGroup) {
        fireEvent(childNode, ProductNodeEvent.NODE_ADDED, nodeGroup);
    }

    protected void fireNodeRemoved(ProductNode childNode, ProductNodeGroup nodeGroup) {
        fireEvent(childNode, ProductNodeEvent.NODE_REMOVED, nodeGroup);
    }

    private void fireEvent(final ProductNode sourceNode, int eventType, ProductNodeGroup nodeGroup) {
        if (hasProductNodeListeners()) {
            final ProductNodeEvent event = new ProductNodeEvent(sourceNode, eventType, nodeGroup);
            fireEvent(event);
        }
    }

    private void fireEvent(final ProductNode sourceNode, final String propertyName, Object oldValue) {
        if (hasProductNodeListeners()) {
            final ProductNodeEvent event = new ProductNodeEvent(sourceNode, propertyName, oldValue);
            fireEvent(event);
        }
    }

    private void fireEvent(final ProductNodeEvent event) {
        fireEvent(event, listeners.toArray(new ProductNodeListener[listeners.size()]));
    }

    static void fireEvent(final ProductNodeEvent event, final ProductNodeListener[] productNodeListeners) {
        for (ProductNodeListener listener : productNodeListeners) {
            fireEvent(event, listener);
        }
    }

    static void fireEvent(final ProductNodeEvent event, final ProductNodeListener listener) {
        switch (event.getType()) {
            case ProductNodeEvent.NODE_CHANGED:
                listener.nodeChanged(event);
                break;
            case ProductNodeEvent.NODE_DATA_CHANGED:
                listener.nodeDataChanged(event);
                break;
            case ProductNodeEvent.NODE_ADDED:
                listener.nodeAdded(event);
                break;
            case ProductNodeEvent.NODE_REMOVED:
                listener.nodeRemoved(event);
                break;
        }
    }

    /**
     * @return The reference number of this product.
     */
    public int getRefNo() {
        return refNo;
    }

    /**
     * Sets the reference number.
     *
     * @param refNo the reference number to set must be in the range 1 .. Integer.MAX_VALUE
     * @throws IllegalArgumentException if the refNo is out of range
     * @throws IllegalStateException
     */
    public void setRefNo(final int refNo) {
        Guardian.assertWithinRange("refNo", refNo, 1, Integer.MAX_VALUE);
        if (this.refNo != 0 && this.refNo != refNo) {
            throw new IllegalStateException("this.refNo != 0 && this.refNo != refNo");
        }
        this.refNo = refNo;
        refStr = "[" + this.refNo + "]";
    }

    public void resetRefNo() {
        refNo = 0;
        refStr = null;
    }

    /**
     * Returns the reference string of this product.
     *
     * @return the reference string.
     */
    String getRefStr() {
        return refStr;
    }

    /**
     * Returns the product manager for this product.
     *
     * @return this product's manager, can be <code>null</code>
     */
    public ProductManager getProductManager() {
        return productManager;
    }

    /**
     * Sets the product manager for this product. Called by a <code>PropductManager</code> to set the product's
     * ownership.
     *
     * @param productManager this product's manager, can be <code>null</code>
     */
    void setProductManager(final ProductManager productManager) {
        this.productManager = productManager;
    }

    //////////////////////////////////////////////////////////////////////////
    // Utilities

    /**
     * Tests if the given band arithmetic expression can be computed using this product.
     *
     * @param expression the mathematical expression
     * @return true, if the band arithmetic is compatible with this product
     * @see #isCompatibleBandArithmeticExpression(String,com.bc.jexp.Parser)
     */
    public boolean isCompatibleBandArithmeticExpression(final String expression) {
        return isCompatibleBandArithmeticExpression(expression, null);
    }

    /**
     * Tests if the given band arithmetic expression can be computed using this product and a given expression parser.
     *
     * @param expression the band arithmetic expression
     * @param parser     the expression parser to be used
     * @return true, if the band arithmetic is compatible with this product
     * @see #createBandArithmeticParser()
     */
    public boolean isCompatibleBandArithmeticExpression(final String expression, Parser parser) {
        Guardian.assertNotNull("expression", expression);
        if (parser == null) {
            parser = createBandArithmeticParser();
        }
        final Term term;
        try {
            term = parser.parse(expression);
        } catch (ParseException e) {
            return false;
        }
        // expression was empty
        if (term == null) {
            return false;
        }

        final RasterDataSymbol[] termSymbols = BandArithmetic.getRefRasterDataSymbols(term);
        for (final RasterDataSymbol termSymbol : termSymbols) {
            final RasterDataNode refRaster = termSymbol.getRaster();
            if (refRaster.getProduct() != this) {
                return false;
            }
            if (termSymbol instanceof SingleFlagSymbol) {
                final String[] flagNames = ((Band) refRaster).getFlagCoding().getFlagNames();
                final String symbolName = termSymbol.getName();
                final String flagName = symbolName.substring(symbolName.indexOf('.') + 1);
                if (!StringUtils.containsIgnoreCase(flagNames, flagName)) {
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * Creates a parser for band arithmetic expressions.
     * The parser created will use a namespace comprising all tie-point grids, bands and flags of this product.
     *
     * @return a parser for band arithmetic expressions for this product, never null
     */
    public Parser createBandArithmeticParser() {
        final Namespace namespace = createBandArithmeticDefaultNamespace();
        return new ParserImpl(namespace, false);
    }

    /**
     * Creates a namespace to be used by parsers for band arithmetic expressions.
     * The namespace created comprises all tie-point grids, bands and flags of this product.
     *
     * @return a namespace, never null
     */
    public WritableNamespace createBandArithmeticDefaultNamespace() {
        return BandArithmetic.createDefaultNamespace(new Product[]{this}, 0);
    }


    /**
     * Creates a subset of this product. The returned product represents a true spatial and spectral subset of this
     * product, but it has not loaded any bands into memory. If name or desc are null or empty, the name and the
     * description from this product was used.
     *
     * @param subsetDef the product subset definition
     * @param name      the name for the new product
     * @param desc      the description for the new product
     * @return the product subset, or <code>null</code> if the product/subset combination is not valid
     * @throws IOException if an I/O error occurs
     */
    public Product createSubset(final ProductSubsetDef subsetDef, final String name, final String desc) throws
            IOException {
        return ProductSubsetBuilder.createProductSubset(this, subsetDef, name, desc);
    }


    /**
     * Creates a map-projected version of this product.
     *
     * @param mapInfo the map information
     * @param name    the name for the new product
     * @param desc    the description for the new product
     * @return the product subset, or <code>null</code> if the product/subset combination is not valid
     * @throws IOException if an I/O error occurs
     */
    public Product createProjectedProduct(final MapInfo mapInfo, final String name, final String desc) throws
            IOException {
        return ProductProjectionBuilder.createProductProjection(this, false, mapInfo, name, desc);
    }

    /**
     * Creates flipped raster-data version of this product.
     *
     * @param flipType the flip type, see <code>{@link org.esa.beam.framework.dataio.ProductFlipper}</code>
     * @param name     the name for the new product
     * @param desc     the description for the new product
     * @return the product subset, or <code>null</code> if the product/subset combination is not valid
     * @throws IOException if an I/O error occurs
     */
    public Product createFlippedProduct(final int flipType, final String name, final String desc) throws IOException {
        return ProductFlipper.createFlippedProduct(this, flipType, name, desc);
    }

    @Override
    public void setModified(final boolean modified) {
        boolean oldSttate = isModified();
        if (oldSttate != modified) {
            super.setModified(modified);
            if (!modified) {
                bandGroup.setModified(false);
                tiePointGridGroup.setModified(false);
                bitmaskDefGroup.setModified(false);
                maskGroup.setModified(false);
                vectorDataGroup.setModified(false);
                flagCodingGroup.setModified(false);
                indexCodingGroup.setModified(false);
                pinGroup.setModified(false);
                gcpGroup.setModified(false);
                getMetadataRoot().setModified(false);
            }
        }
    }

    /**
     * Gets an estimated, raw storage size in bytes of this product node.
     *
     * @param subsetDef if not <code>null</code> the subset may limit the size returned
     * @return the size in bytes.
     */
    @Override
    public long getRawStorageSize(final ProductSubsetDef subsetDef) {
        long size = 0;
        for (int i = 0; i < getNumBands(); i++) {
            size += getBandAt(i).getRawStorageSize(subsetDef);
        }
        for (int i = 0; i < getNumTiePointGrids(); i++) {
            size += getTiePointGridAt(i).getRawStorageSize(subsetDef);
        }
        for (int i = 0; i < getFlagCodingGroup().getNodeCount(); i++) {
            size += getFlagCodingGroup().get(i).getRawStorageSize(subsetDef);
        }
        for (int i = 0; i < getNumBitmaskDefs(); i++) {
            size += getBitmaskDefAt(i).getRawStorageSize(subsetDef);
        }
        size += getMetadataRoot().getRawStorageSize(subsetDef);
        return size;
    }

    /**
     * Gets the name of the band suitable for quicklook generation.
     *
     * @return the name of the quicklook band, or null if none has been defined
     */
    public String getQuicklookBandName() {
        return quicklookBandName;
    }

    /**
     * Sets the name of the band suitable for quicklook generation.
     *
     * @param quicklookBandName the name of the quicklook band, or null
     */
    public void setQuicklookBandName(String quicklookBandName) {
        this.quicklookBandName = quicklookBandName;
    }

//    private static String extractProductName(File file) {
//        Guardian.assertNotNull("file", file);
//        String filename = file.getName();
//        int dotIndex = filename.indexOf('.');
//        if (dotIndex > -1) {
//            filename = filename.substring(0, dotIndex);
//        }
//        return filename;
//    }


    /**
     * Creates a string containing all available information at the given pixel position. The string returned is a line
     * separated text with each line containing a key/value pair.
     *
     * @param pixelX the pixel X co-ordinate
     * @param pixelY the pixel Y co-ordinate
     * @return the info string at the given position
     */
    public String createPixelInfoString(final int pixelX, final int pixelY) {
        final StringBuffer sb = new StringBuffer(1024);

        sb.append("Product:\t");
        sb.append(getName()).append("\n\n");

        sb.append("Image-X:\t");
        sb.append(pixelX);
        sb.append("\tpixel\n");

        sb.append("Image-Y:\t");
        sb.append(pixelY);
        sb.append("\tpixel\n");

        if (getGeoCoding() != null) {
            final PixelPos pt = new PixelPos(pixelX + 0.5f, pixelY + 0.5f);
            final GeoPos geoPos = getGeoCoding().getGeoPos(pt, null);

            sb.append("Longitude:\t");
            sb.append(geoPos.getLonString());
            sb.append("\tdegree\n");

            sb.append("Latitude:\t");
            sb.append(geoPos.getLatString());
            sb.append("\tdegree\n");

            if (getGeoCoding() instanceof MapGeoCoding) {
                final MapGeoCoding mapGeoCoding = (MapGeoCoding) getGeoCoding();
                final MapProjection mapProjection = mapGeoCoding.getMapInfo().getMapProjection();
                final MapTransform mapTransform = mapProjection.getMapTransform();
                final Point2D mapPoint = mapTransform.forward(geoPos, null);
                final String mapUnit = mapProjection.getMapUnit();

                sb.append("Map-X:\t");
                sb.append(mapPoint.getX());
                sb.append("\t").append(mapUnit).append("\n");

                sb.append("Map-Y:\t");
                sb.append(mapPoint.getY());
                sb.append("\t").append(mapUnit).append("\n");
            }
        }

        if (pixelX >= 0 && pixelX < getSceneRasterWidth()
                && pixelY >= 0 && pixelY < getSceneRasterHeight()) {

            sb.append("\n");

            boolean haveSpectralBand = false;
            for (final Band band : getBands()) {
                if (band.getSpectralWavelength() > 0.0) {
                    haveSpectralBand = true;
                    break;
                }
            }

            if (haveSpectralBand) {
                sb.append("BandName\tWavelength\tUnit\tBandwidth\tUnit\tValue\tUnit\tSolar Flux\tUnit\n");
            } else {
                sb.append("BandName\tValue\tUnit\n");
            }
            for (final Band band : getBands()) {
                sb.append(band.getName());
                sb.append(":\t");
                if (band.getSpectralWavelength() > 0.0) {
                    sb.append(band.getSpectralWavelength());
                    sb.append("\t");
                    sb.append("nm");
                    sb.append("\t");
                    sb.append(band.getSpectralBandwidth());
                    sb.append("\t");
                    sb.append("nm");
                    sb.append("\t");
                } else {
                    if (haveSpectralBand) {
                        sb.append("\t");
                        sb.append("\t");
                        sb.append("\t");
                        sb.append("\t");
                    }
                }
                sb.append(band.getPixelString(pixelX, pixelY));
                sb.append("\t");
                if (band.getUnit() != null) {
                    sb.append(band.getUnit());
                }
                sb.append("\t");
                final float solarFlux = band.getSolarFlux();
                if (solarFlux > 0.0) {
                    sb.append(solarFlux);
                    sb.append("\t");
                    sb.append("mW/(m^2*sr*nm)");
                    sb.append("\t");
                }
                sb.append("\n");
            }

            sb.append("\n");
            for (int i = 0; i < getNumTiePointGrids(); i++) {
                final TiePointGrid grid = getTiePointGridAt(i);
                if (grid.hasRasterData()) {
                    sb.append(grid.getName());
                    sb.append(":\t");
                    sb.append(grid.getPixelString(pixelX, pixelY));
                    if (grid.getUnit() != null) {
                        sb.append("\t");
                        sb.append(grid.getUnit());
                    }

                    sb.append("\n");
                }
            }

            for (int i = 0; i < getNumBands(); i++) {
                final Band band = getBandAt(i);
                final FlagCoding flagCoding = band.getFlagCoding();
                if (flagCoding != null) {
                    boolean ioException = false;
                    final int[] flags = new int[1];
                    if (band.hasRasterData()) {
                        flags[0] = band.getPixelInt(pixelX, pixelY);
                    } else {
                        try {
                            band.readPixels(pixelX, pixelY, 1, 1, flags, ProgressMonitor.NULL);
                        } catch (IOException e) {
                            ioException = true;
                        }
                    }
                    sb.append("\n");
                    if (ioException) {
                        sb.append(RasterDataNode.IO_ERROR_TEXT);
                    } else {
                        for (int j = 0; j < flagCoding.getNumAttributes(); j++) {
                            final MetadataAttribute flagAttr = flagCoding.getAttributeAt(j);
                            final int mask = flagAttr.getData().getElemInt();
                            final boolean flagSet = (flags[0] & mask) == mask;
                            sb.append(band.getName());
                            sb.append(".");
                            sb.append(flagAttr.getName());
                            sb.append(":\t");
                            sb.append(flagSet ? "true" : "false");
                            sb.append("\n");
                        }
                    }
                }
            }
        }

        return sb.toString();
    }

    /**
     * @return All removed child nodes. Array may be empty.
     */
    public ProductNode[] getRemovedChildNodes() {
        final ArrayList<ProductNode> removedNodes = new ArrayList<ProductNode>();
        removedNodes.addAll(bandGroup.getRemovedNodes());
        removedNodes.addAll(bitmaskDefGroup.getRemovedNodes());
        removedNodes.addAll(flagCodingGroup.getRemovedNodes());
        removedNodes.addAll(indexCodingGroup.getRemovedNodes());
        removedNodes.addAll(tiePointGridGroup.getRemovedNodes());
        removedNodes.addAll(maskGroup.getRemovedNodes());
        removedNodes.addAll(vectorDataGroup.getRemovedNodes());
        removedNodes.addAll(pinGroup.getRemovedNodes());
        removedNodes.addAll(gcpGroup.getRemovedNodes());
        return removedNodes.toArray(new ProductNode[removedNodes.size()]);
    }


    private void checkGeoCoding(final GeoCoding geoCoding) {
        if (geoCoding instanceof TiePointGeoCoding) {
            final TiePointGeoCoding gc = (TiePointGeoCoding) geoCoding;
            Guardian.assertSame("gc.getLatGrid()", gc.getLatGrid(), getTiePointGrid(gc.getLatGrid().getName()));
            Guardian.assertSame("gc.getLonGrid()", gc.getLonGrid(), getTiePointGrid(gc.getLonGrid().getName()));
        } else if (geoCoding instanceof MapGeoCoding) {
            final MapGeoCoding gc = (MapGeoCoding) geoCoding;
            final MapInfo mapInfo = gc.getMapInfo();
            Guardian.assertNotNull("mapInfo", mapInfo);
            Guardian.assertEquals("mapInfo.getSceneWidth()", mapInfo.getSceneWidth(), getSceneRasterWidth());
            Guardian.assertEquals("mapInfo.getSceneHeight()", mapInfo.getSceneHeight(), getSceneRasterHeight());
        }
    }

    /**
     * Checks whether or not this product can be ortorectified.
     *
     * @return true if {@link Band#canBeOrthorectified()} returns true for all bands, false otherwise
     */
    public boolean canBeOrthorectified() {
        for (int i = 0; i < getNumBands(); i++) {
            if (!getBandAt(i).canBeOrthorectified()) {
                return false;
            }
        }
        return true;
    }

    private String getSuitableBitmaskDefDescription(final BitmaskDef bitmaskDef) {

        final String expr = bitmaskDef.getExpr();
        if (StringUtils.isNullOrEmpty(expr)) {
            return null;
        }

        final Term term;
        try {
            term = createBandArithmeticParser().parse(expr);
        } catch (ParseException e) {
            return null;
        }

        if (term instanceof Term.Ref) {
            return getSuitableBitmaskDefDescription((Term.Ref) term);
        }

        if (term instanceof Term.NotB) {
            final Term.NotB notTerm = ((Term.NotB) term);
            final Term arg = notTerm.getArgs()[0];
            if (arg instanceof Term.Ref) {
                final String description = getSuitableBitmaskDefDescription((Term.Ref) arg);
                if (description != null) {
                    return "Not " + description;
                }
            }
        }

        return null;
    }

    private String getSuitableBitmaskDefDescription(Term.Ref ref) {
        String description = null;
        final String symbolName = ref.getSymbol().getName();
        if (isFlagSymbol(symbolName)) {
            final String[] strings = StringUtils.split(symbolName, new char[]{'.'}, true);
            final String nodeName = strings[0];
            final String flagName = strings[1];
            final RasterDataNode rasterDataNode = getRasterDataNode(nodeName);
            if (rasterDataNode instanceof Band) {
                final FlagCoding flagCoding = ((Band) rasterDataNode).getFlagCoding();
                if (flagCoding != null) {
                    final MetadataAttribute attribute = flagCoding.getAttribute(flagName);
                    if (attribute != null) {
                        description = attribute.getDescription();
                    }
                }
            }
        } else {
            final RasterDataNode rasterDataNode = getRasterDataNode(symbolName);
            if (rasterDataNode != null) {
                description = rasterDataNode.getDescription();
            }
        }
        return description;
    }

    private static boolean isFlagSymbol(final String symbolName) {
        return symbolName.indexOf('.') != -1;
    }


    /**
     * Gets the preferred tile size which may be used for a the {@link java.awt.image.RenderedImage rendered image}
     * created for a {@link RasterDataNode} of this product.
     *
     * @return the preferred tile size, may be <code>null</null> if not specified
     * @see RasterDataNode#getSourceImage()
     * @see RasterDataNode# setSourceImage (java.awt.image.RenderedImage)
     */
    public Dimension getPreferredTileSize() {
        return preferredTileSize;
    }

    /**
     * Sets the preferred tile size which may be used for a the {@link java.awt.image.RenderedImage rendered image}
     * created for a {@link RasterDataNode} of this product.
     *
     * @param tileWidth  the preferred tile width
     * @param tileHeight the preferred tile height
     * @see #setPreferredTileSize(java.awt.Dimension)
     */
    public void setPreferredTileSize(int tileWidth, int tileHeight) {
        setPreferredTileSize(new Dimension(tileWidth, tileHeight));
    }

    /**
     * Sets the preferred tile size which may be used for a the {@link java.awt.image.RenderedImage rendered image}
     * created for a {@link RasterDataNode} of this product.
     *
     * @param preferredTileSize the preferred tile size, may be <code>null</null> if not specified
     * @see RasterDataNode#getSourceImage()
     * @see RasterDataNode# setSourceImage (java.awt.image.RenderedImage)
     */
    public void setPreferredTileSize(Dimension preferredTileSize) {
        this.preferredTileSize = preferredTileSize;
    }

    /**
     * Returns the names of all flags of all flag datasets contained this product.
     * <p/>
     * <p>A flag name contains the dataset (a band of this product) and the actual flag name as defined in the
     * flag-coding associated with the dataset. The general format for the flag name strings returned is therefore
     * <code>"<i>dataset</i>.<i>flag_name</i>"</code>.
     * </p>
     * <p>The method is used to find out which flags a product has in order to use them in bit-mask expressions.
     *
     * @return the array of all flag names. If this product does not support flags, an empty array is returned, but
     *         never <code>null</code>.
     * @see #parseExpression(String)
     */
    public String[] getAllFlagNames() {
        final List<String> l = new ArrayList<String>(32);
        for (int i = 0; i < getNumBands(); i++) {
            final Band band = getBandAt(i);
            if (band.getFlagCoding() != null) {
                for (int j = 0; j < band.getFlagCoding().getNumAttributes(); j++) {
                    final MetadataAttribute attribute = band.getFlagCoding().getAttributeAt(j);
                    l.add(band.getName() + "." + attribute.getName());
                }
            }
        }
        final String[] flagNames = new String[l.size()];
        for (int i = 0; i < flagNames.length; i++) {
            flagNames[i] = l.get(i);
        }
        l.clear();
        return flagNames;
    }

    /////////////////////////////////////////////////////////////////////////
    // Deprecated API

    @Deprecated
    private final ProductNodeGroup<BitmaskDef> bitmaskDefGroup;

    @Deprecated
    private Map<String, BitRaster> validMasks;


    /**
     * Parses a mathematical expression given as a text string.
     *
     * @param expression a expression given as a text string, e.g. "radiance_4 / (1.0 + radiance_11)".
     * @return a term parsed from the given expression string
     * @throws ParseException if the expression could not successfully be parsed
     * @deprecated Since BEAM 4.5.1. Use {@link #parseExpression(String)} instead.
     */
    @Deprecated
    public Term createTerm(final String expression) throws ParseException {
        final Parser parser = createBandArithmeticParser();
        return parser.parse(expression);
    }

    /**
     * Parses a mathematical expression given as a text string.
     *
     * @param expression   a expression given as a text string, e.g. "radiance_4 / (1.0 + radiance_11)".
     * @param extraRasters extra rasters referenced in the given expression
     * @return a term parsed from the given expression string
     * @throws ParseException if the expression could not successfully be parsed
     * @deprecated Since BEAM 4.5.1. Use {@link #parseExpression(String)} instead.
     */
    @Deprecated
    public Term createTerm(final String expression, RasterDataNode... extraRasters) throws ParseException {

        final Product thisProduct = getProductSafe();
        final Product[] products;
        final int thisProductIndex;
        if (thisProduct.getProductManager() != null) {
            products = thisProduct.getProductManager().getProducts();
            thisProductIndex = thisProduct.getProductManager().getProductIndex(thisProduct);
        } else {
            products = new Product[]{thisProduct};
            thisProductIndex = 0;
        }
        Assert.state(thisProductIndex >= products.length && thisProductIndex < products.length);
        Assert.state(products[thisProductIndex] == thisProduct);

        final WritableNamespace namespace = BandArithmetic.createDefaultNamespace(products, thisProductIndex);
        final Symbol[] symbols = namespace.getAllSymbols();
        for (RasterDataNode extraRaster : extraRasters) {
            boolean registered = false;
            for (Symbol symbol : symbols) {
                if (symbol.getName().equals(extraRaster.getName())) {
                    registered = true;
                    break;
                }
            }
            if (!registered) {
                namespace.registerSymbol(new RasterDataSymbol(extraRaster.getName(),
                                                              extraRaster, RasterDataSymbol.GEOPHYSICAL));
            }
        }
        final Parser parser = new ParserImpl(namespace, false);
        return parser.parse(expression);
    }


    /**
     * Adds the given flag coding to this product.
     *
     * @param flagCoding the flag coding to added, ignored if <code>null</code>
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public void addFlagCoding(final FlagCoding flagCoding) {
        getFlagCodingGroup().add(flagCoding);
    }

    /**
     * Removes the given flag coding from this product.
     *
     * @param flagCoding the flag coding to be removed, ignored if <code>null</code>
     * @return <code>true</code> on success
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public boolean removeFlagCoding(final FlagCoding flagCoding) {
        return getFlagCodingGroup().remove(flagCoding);
    }

    /**
     * Returns the number of flag codings contained in this product.
     *
     * @return the number of flag codings
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public int getNumFlagCodings() {
        return getFlagCodingGroup().getNodeCount();
    }

    /**
     * Returns the flag coding at the given index.
     *
     * @param index the flag coding index
     * @return the flag coding at the given index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public FlagCoding getFlagCodingAt(final int index) {
        return getFlagCodingGroup().get(index);
    }


    /**
     * Returns a string array containing the names of the flag codings contained in this product
     *
     * @return a string array containing the names of the flag codings contained in this product. If this product has no
     *         flag coding a zero-length-array is returned.
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public String[] getFlagCodingNames() {
        return getFlagCodingGroup().getNodeNames();
    }

    /**
     * Returns the flag coding with the given name.
     *
     * @param name the flag coding name
     * @return the flag coding with the given name or <code>null</code> if a flag coding with the given name is not
     *         contained in this product.
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public FlagCoding getFlagCoding(final String name) {
        return getFlagCodingGroup().get(name);
    }

    /**
     * Tests if a flag coding with the given name is contained in this product.
     *
     * @param name the name, must not be <code>null</code>
     * @return <code>true</code> if a flag coding with the given name is contained in this product, <code>false</code>
     *         otherwise
     * @deprecated since BEAM 4.2, use {@link #getFlagCodingGroup()} instead
     */
    @Deprecated
    public boolean containsFlagCoding(final String name) {
        return getFlagCodingGroup().contains(name);
    }

    //////////////////////////////////////////////////////////////////////////
    // Bitmask definitions support

    /**
     * Adds the given bitmask definition to this product.
     *
     * @param bitmaskDef the bitmask definition to added, ignored if <code>null</code>
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void addBitmaskDef(final BitmaskDef bitmaskDef) {
        if (StringUtils.isNullOrEmpty(bitmaskDef.getDescription())) {
            final String defaultDescription = getSuitableBitmaskDefDescription(bitmaskDef);
            bitmaskDef.setDescription(defaultDescription);
        }
        bitmaskDefGroup.add(bitmaskDef);
        maskGroup.add(bitmaskDef.createMask(sceneRasterWidth, sceneRasterHeight));
    }

    /**
     * Moves the given bitmask definition to the given index.
     *
     * @param bitmaskDef the bitmask definition which is to move
     * @param index      the destination index for the given bitmask definition
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void moveBitmaskDef(final BitmaskDef bitmaskDef, final int index) {
        Mask mask = getMaskGroup().get(bitmaskDef.getName());
        if (bitmaskDefGroup.remove(bitmaskDef)) {
            fireNodeRemoved(bitmaskDef, bitmaskDefGroup);
            getMaskGroup().remove(mask);
        }
        bitmaskDefGroup.add(index, bitmaskDef);
        fireNodeAdded(bitmaskDef, bitmaskDefGroup);
        getMaskGroup().add(index, mask);
    }

    /**
     * Removes the given bitmask definition from this product.
     *
     * @param bitmaskDef the bitmask definition to be removed, ignored if <code>null</code>
     * @return <code>true</code> on success
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public boolean removeBitmaskDef(final BitmaskDef bitmaskDef) {
        final boolean result = bitmaskDefGroup.remove(bitmaskDef);
        removeBitmaskDef(getBands(), bitmaskDef);
        removeBitmaskDef(getTiePointGrids(), bitmaskDef);

        Mask mask = getMaskGroup().get(bitmaskDef.getName());
        getMaskGroup().remove(mask);

        return result;
    }

    @Deprecated
    private static void removeBitmaskDef(RasterDataNode[] bands, BitmaskDef bitmaskDef) {
        for (RasterDataNode band : bands) {
            final BitmaskOverlayInfo bitmaskOverlayInfo = band.getBitmaskOverlayInfo();
            if (bitmaskOverlayInfo != null) {
                bitmaskOverlayInfo.removeBitmaskDef(bitmaskDef);
            }
        }
    }

    /**
     * Gets the number of bitmask definitions contained in this product.
     *
     * @return the number of bitmask definitions
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public int getNumBitmaskDefs() {
        return bitmaskDefGroup.getNodeCount();
    }

    /**
     * Returns the bitmask definition at the given index.
     *
     * @param index the bitmask definition index
     * @return the bitmask definition at the given index
     * @throws IndexOutOfBoundsException if the index is out of bounds
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitmaskDef getBitmaskDefAt(final int index) {
        return bitmaskDefGroup.get(index);
    }

    /**
     * Returns a string array containing the names of the bitmask definitions contained in this product.
     *
     * @return a string array containing the names of the bitmask definitions contained in this product. If this product
     *         has no bitmask definitions a zero-length-array is returned.
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public String[] getBitmaskDefNames() {
        return bitmaskDefGroup.getNodeNames();
    }

    /**
     * Returns the bitmask definition with the given name.
     *
     * @param name the bitmask definition name
     * @return the bitmask definition with the given name or <code>null</code> if a bitmask definition with the given
     *         name is not contained in this product.
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitmaskDef getBitmaskDef(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return bitmaskDefGroup.get(name);
    }

    /**
     * Returns an array of bitmask definitions contained in this product
     *
     * @return an array of bitmask definition contained in this product. If this product has no bitmask definitions a
     *         zero-length-array is returned.
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitmaskDef[] getBitmaskDefs() {
        final BitmaskDef[] bitmaskDefs = new BitmaskDef[getNumBitmaskDefs()];
        for (int i = 0; i < bitmaskDefs.length; i++) {
            bitmaskDefs[i] = getBitmaskDefAt(i);
        }
        return bitmaskDefs;
    }

    /**
     * Tests if a bitmask definition with the given name is contained in this product.
     *
     * @param name the name, must not be <code>null</code>
     * @return <code>true</code> if a bitmask definition with the given name is contained in this product,
     *         <code>false</code> otherwise
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public boolean containsBitmaskDef(final String name) {
        Guardian.assertNotNullOrEmpty("name", name);
        return bitmaskDefGroup.contains(name);
    }

    /**
     * Tests if the given bitmask definition is contained in this container.
     *
     * @param def the bitmask definition, must not be <code>null</code>
     * @return <code>true</code> if the bitmask definition is contained in this cotainer, <code>false</code> otherwise
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public boolean containsBitmaskDef(final BitmaskDef def) {
        if (def != null) {
            final BitmaskDef containedDef = getBitmaskDef(def.getName());
            return def == containedDef;
        }
        return false;
    }

    /**
     * Checks whether or not the given bitmask definition is compatible with this product.
     *
     * @param bitmaskDef The bitmask definition.
     * @return <code>false</code> if the bitmask has a valid expression and(!) the flag name is not contained in this
     *         data product, <code>true</code> otherwise.
     * @deprecated since BEAM 4.7
     */
    @Deprecated
    public boolean isCompatibleBitmaskDef(final BitmaskDef bitmaskDef) {
        try {
            parseExpression(bitmaskDef.getExpr());
        } catch (ParseException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets a valid-mask for the given ID.
     *
     * @param id the ID
     * @return a cached valid mask for the given ID or null
     * @see #createValidMask(String,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitRaster getValidMask(final String id) {
        if (validMasks != null) {
            return validMasks.get(id);
        }
        return null;
    }

    /**
     * Sets a valid-mask for the given ID.
     *
     * @param id        the ID
     * @param validMask the pixel mask
     * @see #createValidMask(String,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void setValidMask(final String id, final BitRaster validMask) {
        if (validMask != null) {
            Guardian.assertEquals("validMask", validMask.getWidth(), getSceneRasterWidth());
            Guardian.assertEquals("validMask", validMask.getHeight(), getSceneRasterHeight());
            if (validMasks == null) {
                validMasks = new HashMap<String, BitRaster>();
            }
            validMasks.put(id, validMask);
        } else {
            if (validMasks != null) {
                validMasks.remove(id);
            }
        }
    }

    /**
     * Creates a bit-packed valid-mask for all pixels of the scene covered by this product.
     * The given expression is considered to be boolean, if it evaluates to <code>true</code>
     * the related bit in the mask is set.
     *
     * @param expression the boolean expression, e.g. "l2_flags.LAND && reflec_10 >= 0.0"
     * @param pm         a progress monitor
     * @return a bit-packed mask for all pixels of the scene, never null
     * @throws IOException if an I/O error occurs
     * @see #parseExpression(String)
     * @see #createValidMask(com.bc.jexp.Term,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitRaster createValidMask(final String expression, final ProgressMonitor pm) throws IOException {
        try {
            final Term term = getProduct().parseExpression(expression);
            return createValidMask(term, pm);
        } catch (ParseException e) {
            final IOException ioException = new IOException(
                    "Unable to load the valid pixel mask, parse error: " + e.getMessage());
            ioException.initCause(e);
            throw ioException;
        }
    }

    /**
     * Creates a bit-packed mask for all pixels of the scene covered by this product.
     * The given term is considered to be boolean, if it evaluates to <code>true</code>
     * the related bit in the mask is set.
     * <p>Since the masks created by this method are cached, the method {@link #releaseValidMask(org.esa.beam.util.BitRaster)}
     * should be called in order to release it.
     *
     * @param term the boolean term, e.g. "l2_flags.LAND && reflec_10 >= 0.0"
     * @param pm   a progress monitor
     * @return a bit-packed mask for all pixels of the scene, never null
     * @throws IOException if an I/O error occurs
     * @see #createValidMask(String,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public BitRaster createValidMask(final Term term, final ProgressMonitor pm) throws IOException {
        final String id = term.toString();
        BitRaster cachedValidMask = getValidMask(id);
        if (cachedValidMask != null) {
            return cachedValidMask;
        }
        Debug.trace("createValidMask: " + id);
        final int productWidth = getSceneRasterWidth();
        final int productHeight = getSceneRasterHeight();
        final BitRaster validMask = new BitRaster(productWidth, productHeight);

        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        final RasterDataLoop loop = new RasterDataLoop(0, 0,
                                                       productWidth, productHeight,
                                                       new Term[]{term}, pm);
        loop.forEachPixel(new RasterDataLoop.Body() {
            @Override
            public void eval(final RasterDataEvalEnv env, final int pixelIndex) {
                if (term.evalB(env)) {
                    validMask.set(pixelIndex);
                }
            }
        }, "Computing valid-mask..."); /*I18N*/

        setValidMask(id, validMask);

        stopWatch.stopAndTrace("createValidMask");

        return validMask;
    }

    /**
     * Releases a valid-mask previously allocated with the {@link #createValidMask(String,com.bc.ceres.core.ProgressMonitor) createValidMask()} method.
     *
     * @param validMask the pixel mask to be released
     * @see #createValidMask(String,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void releaseValidMask(final BitRaster validMask) {
        if (validMasks != null) {
            final Collection<BitRaster> bitRasters = validMasks.values();
            for (Iterator<BitRaster> iterator = bitRasters.iterator(); iterator.hasNext();) {
                final BitRaster bitRaster = iterator.next();
                if (validMask == bitRaster) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    /**
     * @see #readBitmask(int,int,int,int,com.bc.jexp.Term,boolean[],com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void readBitmask(final int offsetX,
                            final int offsetY,
                            final int width,
                            final int height,
                            final Term bitmaskTerm,
                            final boolean[] bitmask) throws IOException {
        readBitmask(offsetX, offsetY, width, height, bitmaskTerm, bitmask, ProgressMonitor.NULL);
    }

    /**
     * Creates a bit-mask by evaluating the given bit-mask term.
     * <p> The method first creates an evaluation context for the given bit-mask term and the specified region and then
     * evaluates the term for each pixel in the subset (line-by-line, X varies fastest). The result of each evaluation -
     * the resulting bitmask - is stored in the given boolean array buffer <code>bitmask</code> in the same order as
     * pixels appear in the given region. The buffer must at least have a length equal to <code>width * height</code>
     * elements.
     * </p>
     * <p> If flag providing datasets are referenced in the given bit-mask expression which are currently not completely
     * loaded, the method reloads the spatial subset from the data source in order to create the evaluation context.
     * </p>
     * <p> The {@link #parseExpression(String)} method can be used to create a bit-mask
     * term from a textual bit-mask expression.
     * </p>
     *
     * @param offsetX     the X-offset of the spatial subset in pixel co-ordinates
     * @param offsetY     the Y-offset of the spatial subset in pixel co-ordinates
     * @param width       the width of the spatial subset in pixel co-ordinates
     * @param height      the height of the spatial subset in pixel co-ordinates
     * @param bitmaskTerm a bit-mask term, as returned by the {@link #parseExpression(String)} method
     * @param bitmask     a buffer used to hold the results of the bit-mask evaluations for each pixel in the given
     *                    spatial subset
     * @param pm          a monitor to inform the user about progress
     * @throws IOException if an I/O error occurs, when referenced flag datasets are reloaded
     * @see #parseExpression(String)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void readBitmask(final int offsetX,
                            final int offsetY,
                            final int width,
                            final int height,
                            final Term bitmaskTerm,
                            final boolean[] bitmask, ProgressMonitor pm) throws IOException {
        final RasterDataLoop loop = new RasterDataLoop(offsetX, offsetY,
                                                       width, height,
                                                       new Term[]{bitmaskTerm}, pm);
        loop.forEachPixel(new RasterDataLoop.Body() {
            @Override
            public void eval(final RasterDataEvalEnv env, final int pixelIndex) {
                bitmask[pixelIndex] = bitmaskTerm.evalB(env);
            }
        });
    }

    /**
     * @see #readBitmask(int,int,int,int,com.bc.jexp.Term,byte[],byte,byte,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    public synchronized void readBitmask(final int offsetX,
                                         final int offsetY,
                                         final int width,
                                         final int height,
                                         final Term bitmaskTerm,
                                         final byte[] bitmask,
                                         final byte trueValue,
                                         final byte falseValue) throws IOException {
        readBitmask(offsetX, offsetY, width, height, bitmaskTerm, bitmask, trueValue, falseValue, ProgressMonitor.NULL);
    }


    /**
     * Creates a bit-mask by evaluating the given bit-mask term.
     * <p/>
     * <p> The method first creates an evaluation context for the given bit-mask term and the specified region and then
     * evaluates the term for each pixel in the subset (line-by-line, X varies fastest). The result of each evaluation -
     * the resulting bitmask - is stored in the given boolean array buffer <code>bitmask</code> in the same order as
     * pixels appear in the given region. The buffer must at least have a length equal to <code>width * height</code>
     * elements.
     * </p>
     * <p> If flag providing datasets are referenced in the given bit-mask expression which are currently not completely
     * loaded, the method reloads the spatial subset from the data source in order to create the evaluation context.
     * </p>
     * <p> The {@link #parseExpression(String)} method can be used to create a bit-mask
     * term from a textual bit-mask expression.
     *
     * @param offsetX     the X-offset of the spatial subset in pixel co-ordinates
     * @param offsetY     the Y-offset of the spatial subset in pixel co-ordinates
     * @param width       the width of the spatial subset in pixel co-ordinates
     * @param height      the height of the spatial subset in pixel co-ordinates
     * @param bitmaskTerm a bit-mask term, as returned by the {@link #parseExpression(String)}
     *                    method
     * @param bitmask     a byte buffer used to hold the results of the bit-mask evaluations for each pixel in the given
     *                    spatial subset
     * @param trueValue   the byte value to be set if the bitmask-term evauates to <code>true</code>
     * @param falseValue  the byte value to be set if the bitmask-term evauates to <code>false</code>
     * @throws IOException if an I/O error occurs, when referenced flag datasets are reloaded
     * @see #parseExpression(String)
     * @see #readBitmask(int,int,int,int,Term,int[],int,int)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public synchronized void readBitmask(final int offsetX,
                                         final int offsetY,
                                         final int width,
                                         final int height,
                                         final Term bitmaskTerm,
                                         final byte[] bitmask,
                                         final byte trueValue,
                                         final byte falseValue,
                                         ProgressMonitor pm) throws IOException {
        final RasterDataLoop loop = new RasterDataLoop(offsetX, offsetY,
                                                       width, height,
                                                       new Term[]{bitmaskTerm}, pm);
        loop.forEachPixel(new RasterDataLoop.Body() {
            @Override
            public void eval(final RasterDataEvalEnv env, final int pixelIndex) {
                if (bitmaskTerm.evalB(env)) {
                    bitmask[pixelIndex] = trueValue;
                } else {
                    bitmask[pixelIndex] = falseValue;
                }
            }
        }, "Reading bitmask...");  /*I18N*/
    }


    /**
     * Creates a bit-mask by evaluating the given bit-mask term.
     * <p/>
     * <p> The method works exactly like {@link #readBitmask(int,int,int,int,Term,byte[],byte,byte)},
     * but in this method the bitmnask is represented by an array of integers to be filled.
     *
     * @param offsetX     the X-offset of the spatial subset in pixel co-ordinates
     * @param offsetY     the Y-offset of the spatial subset in pixel co-ordinates
     * @param width       the width of the spatial subset in pixel co-ordinates
     * @param height      the height of the spatial subset in pixel co-ordinates
     * @param bitmaskTerm a bit-mask term, as returned by the {@link #parseExpression(String)}
     *                    method
     * @param bitmask     an integer buffer used to hold the results of the bit-mask evaluations for each pixel in the
     *                    given spatial subset
     * @param trueValue   the integer value to be set if the bitmask-term evauates to <code>true</code>
     * @param falseValue  the integer value to be set if the bitmask-term evauates to <code>false</code>
     * @throws IOException if an I/O error occurs, when referenced flag datasets are reloaded
     * @see #parseExpression(String)
     * @see #readBitmask(int,int,int,int,Term,byte[],byte,byte)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void readBitmask(final int offsetX,
                            final int offsetY,
                            final int width,
                            final int height,
                            final Term bitmaskTerm,
                            final int[] bitmask,
                            final int trueValue,
                            final int falseValue) throws IOException {
        readBitmask(offsetX, offsetY, width, height, bitmaskTerm, bitmask, trueValue, falseValue, ProgressMonitor.NULL);
    }

    /**
     * Creates a bit-mask by evaluating the given bit-mask term.
     * <p/>
     * <p> The method works exactly like {@link #readBitmask(int,int,int,int,Term,byte[],byte,byte)},
     * but in this method the bitmnask is represented by an array of integers to be filled.
     *
     * @param offsetX     the X-offset of the spatial subset in pixel co-ordinates
     * @param offsetY     the Y-offset of the spatial subset in pixel co-ordinates
     * @param width       the width of the spatial subset in pixel co-ordinates
     * @param height      the height of the spatial subset in pixel co-ordinates
     * @param bitmaskTerm a bit-mask term, as returned by the {@link #parseExpression(String)}
     *                    method
     * @param bitmask     an integer buffer used to hold the results of the bit-mask evaluations for each pixel in the
     *                    given spatial subset
     * @param trueValue   the integer value to be set if the bitmask-term evauates to <code>true</code>
     * @param falseValue  the integer value to be set if the bitmask-term evauates to <code>false</code>
     * @param pm          a monitor to inform the user about progress
     * @throws IOException if an I/O error occurs, when referenced flag datasets are reloaded
     * @see #parseExpression(String)
     * @see #readBitmask(int,int,int,int,Term,byte[],byte,byte)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void readBitmask(final int offsetX,
                            final int offsetY,
                            final int width,
                            final int height,
                            final Term bitmaskTerm,
                            final int[] bitmask,
                            final int trueValue,
                            final int falseValue,
                            ProgressMonitor pm) throws IOException {
        final RasterDataLoop loop = new RasterDataLoop(offsetX, offsetY, width, height, new Term[]{bitmaskTerm}, pm);
        loop.forEachPixel(new RasterDataLoop.Body() {
            @Override
            public void eval(final RasterDataEvalEnv env, final int pixelIndex) {
                if (bitmaskTerm.evalB(env)) {
                    bitmask[pixelIndex] = trueValue;
                } else {
                    bitmask[pixelIndex] = falseValue;
                }
            }
        });
    }

    /**
     * @see #maskProductData(int,int,int,int,com.bc.jexp.Term,ProductData,boolean,double,com.bc.ceres.core.ProgressMonitor)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void maskProductData(final int offsetX,
                                final int offsetY,
                                final int width,
                                final int height,
                                final Term bitmaskTerm,
                                final ProductData rasterData,
                                final boolean termValue,
                                final double maskValue) throws IOException {
        maskProductData(offsetX, offsetY,
                        width, height,
                        bitmaskTerm,
                        rasterData,
                        termValue, maskValue,
                        ProgressMonitor.NULL);
    }

    /**
     * Masks the given raster data for the specified region using the a given bit-mask term.
     * <p> The method evaluates the term for each pixel in the subset (line-by-line, X varies fastest).
     * If the bit-mask term evaluates to <code>termValue</code> at the current pixel position, then <code>maskValue</code> is set at the corrresponding
     * position in <code>rasterData</code> instead of the original pixel value. If the bit-mask term does not forEachPixel
     * to <code>termValue</code> then the original pixel value in <code>rasterData</code> remains unchanged. The
     * buffer must at least have a length equal to <code>width * height</code> elements.
     * </p>
     * <p> The {@link #parseExpression(String) createTerm} method can be used to create a bit-mask
     * term from a textual bit-mask expression.
     *
     * @param offsetX     the X-offset of the spatial subset in pixel co-ordinates
     * @param offsetY     the Y-offset of the spatial subset in pixel co-ordinates
     * @param width       the width of the spatial subset in pixel co-ordinates
     * @param height      the height of the spatial subset in pixel co-ordinates
     * @param bitmaskTerm a bit-mask term, as returned by the {@link #parseExpression(String) createTerm}
     *                    method
     * @param rasterData  the raster data which is masked with  <code>maskPixelValue</code> if the term evaluates to
     *                    <code>termValue</code> at a given pixel position
     * @param termValue   the term evaluation value which controls the masking
     * @param maskValue   the pixel value which is set if the term evaluates to <code>termValue</code>
     * @param pm          a monitor to inform the user about progress
     * @throws IOException if an I/O error occurs, when referenced flag datasets are reloaded
     * @see #parseExpression(String)
     * @deprecated since BEAM 4.7, use {@link #getMaskGroup()} instead
     */
    @Deprecated
    public void maskProductData(final int offsetX,
                                final int offsetY,
                                final int width,
                                final int height,
                                final Term bitmaskTerm,
                                final ProductData rasterData,
                                final boolean termValue,
                                final double maskValue,
                                final ProgressMonitor pm) throws IOException {
        final RasterDataLoop loop = new RasterDataLoop(offsetX, offsetY,
                                                       width, height,
                                                       new Term[]{bitmaskTerm},
                                                       pm);
        loop.forEachPixel(new RasterDataLoop.Body() {
            @Override
            public void eval(final RasterDataEvalEnv env, final int pixelIndex) {
                if (bitmaskTerm.evalB(env) == termValue) {
                    rasterData.setElemDoubleAt(pixelIndex, maskValue);
                }
            }
        });
    }

}






