package org.esa.beam.gpf.common.mosaic;

import com.bc.ceres.binding.Converter;
import com.bc.ceres.binding.ConverterRegistry;
import com.bc.ceres.glevel.MultiLevelImage;
import com.bc.jexp.ParseException;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.CrsGeoCoding;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.dataop.barithm.BandArithmetic;
import org.esa.beam.framework.gpf.GPF;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.SourceProducts;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.jai.ImageManager;
import org.esa.beam.jai.ResolutionLevel;
import org.esa.beam.jai.VirtualBandOpImage;
import org.esa.beam.util.jai.JAIUtils;
import org.geotools.factory.Hints;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.geometry.Envelope;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.media.jai.ImageLayout;
import javax.media.jai.JAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.operator.AddCollectionDescriptor;
import javax.media.jai.operator.AddDescriptor;
import javax.media.jai.operator.FormatDescriptor;
import javax.media.jai.operator.MosaicDescriptor;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.DataBuffer;
import java.awt.image.RenderedImage;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Marco Peters
 * @since BEAM 4.7
 */
@OperatorMetadata(alias = "Mosaic",
                  version = "1.0",
                  authors = "Marco Peters, Ralf Quast, Marco Zühlke",
                  copyright = "(c) 2009 by Brockmann Consult",
                  description = "Creates a mosaic out of a set of source products.",
                  internal = true)
@SuppressWarnings({"PackageVisibleField"})
public class MosaicOp extends Operator {

    @SourceProducts(count = -1, description = "The source products to be used for mosaicking.")
    Product[] sourceProducts;

    @SourceProduct(description = "A product to be updated.", optional = true)
    Product updateProduct;

    @TargetProduct
    Product targetProduct;

    @Parameter(itemAlias = "variable", description = "Specifies the bands in the target product.")
    Variable[] variables;

    @Parameter(itemAlias = "condition", description = "Specifies valid pixels considered in the target product.")
    Condition[] conditions;

    @Parameter(description = "Specifies the way how conditions are combined.", defaultValue = "OR",
               valueSet = {"OR", "AND"})
    String combine;

    @Parameter(defaultValue = "EPSG:4326",
               description = "The CRS of the target product, represented as WKT or authority code.")
    String crs;

    @Parameter(description = "Wether the source product should be orthorectified.", defaultValue = "false")
    boolean orthorectify;
    @Parameter(description = "The name of the elevation model for the orthorectification.")
    String elevationModelName;

    @Parameter(alias = "resampling", label = "Resampling Method", description = "The method used for resampling.",
               valueSet = {"Nearest", "Bilinear", "Bicubic"}, defaultValue = "Nearest")
    String resamplingName;

    @Parameter(description = "The western longitude.", interval = "[-180,180]", defaultValue = "-15.0")
    double westBound;
    @Parameter(description = "The northern latitude.", interval = "[-90,90]", defaultValue = "75.0")
    double northBound;
    @Parameter(description = "The eastern longitude.", interval = "[-180,180]", defaultValue = "30")
    double eastBound;
    @Parameter(description = "The southern latitude.", interval = "[-90,90]", defaultValue = "35.0")
    double southBound;

    @Parameter(description = "Size of a pixel in X-direction in map units.", defaultValue = "0.05")
    double pixelSizeX;
    @Parameter(description = "Size of a pixel in Y-direction in map units.", defaultValue = "0.05")
    double pixelSizeY;

    private Product[] reprojectedProducts;


    @Override
    public void initialize() throws OperatorException {
        if (isUpdateMode()) {
            initFields();
            targetProduct = updateProduct;
            updateMetadata(targetProduct);
        } else {
            targetProduct = createTargetProduct();
        }
        reprojectedProducts = createReprojectedProducts();

        // for each variable and each product one 'alpha' image is created.
        // the alpha value for a pixel is either 0.0 or 1.0
        List<List<PlanarImage>> alphaImageList = createAlphaImages();

        // for each variable and each product one 'source' image is created.
        List<List<RenderedImage>> sourceImageList = createSourceImages();

        List<RenderedImage> mosaicImageList = createMosaicImages(sourceImageList, alphaImageList);

        final List<RenderedImage> variableCountImageList = createVariableCountImages(alphaImageList);
        setTargetBandImages(targetProduct, mosaicImageList, variableCountImageList);
        reprojectedProducts = null;
    }


    private void updateMetadata(Product product) {
        final MetadataElement graphElement = product.getMetadataRoot().getElement("Processing_Graph");
        for (MetadataElement nodeElement : graphElement.getElements()) {
            if (getSpi().getOperatorAlias().equals(nodeElement.getAttributeString("operator"))) {
                final MetadataElement sourcesElement = nodeElement.getElement("sources");
                for (int i = 0; i < sourceProducts.length; i++) {
                    final String oldIndex = String.valueOf(i + 1);
                    final String newIndex = String.valueOf(sourcesElement.getNumAttributes() + i + 1);
                    final Product sourceProduct = sourceProducts[i];
                    final String attributeName = getSourceProductId(sourceProduct).replaceFirst(oldIndex, newIndex);
                    final File location = sourceProduct.getFileLocation();
                    final ProductData attributeValue;
                    if (location == null) {
                        attributeValue = ProductData.createInstance(product.toString());
                    } else {
                        attributeValue = ProductData.createInstance(location.getPath());
                    }
                    final MetadataAttribute attribute = new MetadataAttribute(attributeName, attributeValue, true);
                    sourcesElement.addAttribute(attribute);
                }
            }
        }
    }

    private void initFields() {
        final Map<String, Object> params = getOperatorParameters(updateProduct);
        initObject(params, this);
    }

    private List<RenderedImage> createVariableCountImages(List<List<PlanarImage>> alphaImageList) {
        List<RenderedImage> variableCountImageList = new ArrayList<RenderedImage>(variables.length);
        for (List<PlanarImage> variableAlphaImageList : alphaImageList) {
            final RenderedImage countFloatImage = createImageSum(variableAlphaImageList);
            variableCountImageList.add(FormatDescriptor.create(countFloatImage, DataBuffer.TYPE_INT, null));
        }
        return variableCountImageList;
    }

    private List<List<PlanarImage>> createAlphaImages() {
        final List<List<PlanarImage>> alphaImageList = new ArrayList<List<PlanarImage>>(variables.length);
        for (final Variable variable : variables) {
            final ArrayList<PlanarImage> list = new ArrayList<PlanarImage>(reprojectedProducts.length);
            alphaImageList.add(list);
            for (final Product product : reprojectedProducts) {
                final String validMaskExpression;
                try {
                    validMaskExpression = createValidMaskExpression(product, variable.getExpression());
                } catch (ParseException e) {
                    throw new OperatorException(e);
                }
                final StringBuilder combinedExpression = new StringBuilder(validMaskExpression);
                if (conditions != null && conditions.length > 0) {
                    combinedExpression.append(" && (");
                    for (int i = 0; i < conditions.length; i++) {
                        Condition condition = conditions[i];
                        if (i != 0) {
                            combinedExpression.append(" ").append(combine).append(" ");
                        }
                        combinedExpression.append(condition.getExpression());
                    }
                    combinedExpression.append(")");
                }
                list.add(createExpressionImage(combinedExpression.toString(), product));
            }
            if (isUpdateMode()) {
                final RenderedImage updateImage = updateProduct.getBand(getCountBandName(variable)).getSourceImage();
                list.add(FormatDescriptor.create(updateImage, DataBuffer.TYPE_FLOAT, null));
            }
        }
        return alphaImageList;
    }

    private static String createValidMaskExpression(Product product, final String expression) throws ParseException {
        return BandArithmetic.getValidMaskExpression(expression, new Product[]{product}, 0, null);
    }

    private List<RenderedImage> createMosaicImages(List<List<RenderedImage>> sourceImageList,
                                                   List<List<PlanarImage>> alphaImageList) {
        ImageLayout imageLayout = ImageManager.createSingleBandedImageLayout(
                ImageManager.getDataBufferType(ProductData.TYPE_FLOAT32),
                targetProduct.getSceneRasterWidth(),
                targetProduct.getSceneRasterHeight(),
                ImageManager.getPreferredTileSize(targetProduct),
                ResolutionLevel.MAXRES);
        Hints hints = new Hints(JAI.KEY_IMAGE_LAYOUT, imageLayout);
        final List<RenderedImage> mosaicImages = new ArrayList<RenderedImage>(sourceImageList.size());
        for (int i = 0; i < sourceImageList.size(); i++) {
            final PlanarImage[] sourceAlphas = alphaImageList.get(i).toArray(
                    new PlanarImage[alphaImageList.size()]);
            final List<RenderedImage> sourceImages = sourceImageList.get(i);
            final RenderedImage[] renderedImages = sourceImages.toArray(new RenderedImage[sourceImages.size()]);
            // we don't need ROIs, cause they are not considered by MosaicDescriptor when sourceAlphas are given
            mosaicImages.add(MosaicDescriptor.create(renderedImages, MosaicDescriptor.MOSAIC_TYPE_BLEND,
                                                     sourceAlphas, null, null, null, hints));
        }

        return mosaicImages;
    }

    private void setTargetBandImages(Product product, List<RenderedImage> bandImages,
                                     List<RenderedImage> variableCountImageList) {
        for (int i = 0; i < variables.length; i++) {
            Variable outputVariable = variables[i];
            product.getBand(outputVariable.getName()).setSourceImage(bandImages.get(i));

            final String countBandName = getCountBandName(outputVariable);
            product.getBand(countBandName).setSourceImage(variableCountImageList.get(i));
        }

        if (conditions != null) {
            for (Condition condition : conditions) {
                if (condition.isOutput()) {
                    // The sum of all conditions of all sources is created.
                    // 1.0 indicates condition is true and 0.0 indicates false.
                    final RenderedImage sumImage = createConditionSumImage(condition);
                    final RenderedImage reformattedImage = FormatDescriptor.create(sumImage, DataBuffer.TYPE_INT, null);
                    RenderedImage condImage = reformattedImage;
                    if (isUpdateMode()) {
                        final RenderedImage updateimage = updateProduct.getBand(condition.getName()).getSourceImage();
                        condImage = AddDescriptor.create(reformattedImage, updateimage, null);
                    }
                    Band band = product.getBand(condition.getName());
                    band.setSourceImage(condImage);
                }
            }
        }
    }

    private String getCountBandName(Variable outputVariable) {
        return String.format("%s_count", outputVariable.getName());
    }

    private RenderedImage createConditionSumImage(Condition condition) {
        final List<RenderedImage> renderedImageList = new ArrayList<RenderedImage>(reprojectedProducts.length);
        for (Product reprojectedProduct : reprojectedProducts) {
            renderedImageList.add(createConditionImage(condition, reprojectedProduct));
        }
        return createImageSum(renderedImageList);
    }

    private PlanarImage createConditionImage(Condition condition, Product reprojectedProduct) {
        String validMaskExpression;
        try {
            validMaskExpression = createValidMaskExpression(reprojectedProduct, condition.getExpression());
        } catch (ParseException e) {
            throw new OperatorException(e);
        }
        String expression = validMaskExpression + " && (" + condition.getExpression() + ")";
        // the condition images are used as sourceAlpha parameter for MosaicOpImage, they have to have the same
        // data type as the source images. That's why we use normal expression images with data type FLOAT32.
        return createExpressionImage(expression, reprojectedProduct);
    }

    private RenderedImage createImageSum(List<? extends RenderedImage> renderedImageList) {
        if (renderedImageList.size() >= 2) {
            return AddCollectionDescriptor.create(renderedImageList, null);
        } else {
            return renderedImageList.get(0);
        }
    }

    private List<List<RenderedImage>> createSourceImages() {
        final List<List<RenderedImage>> sourceImageList = new ArrayList<List<RenderedImage>>(variables.length);
        for (final Variable variable : variables) {
            final List<RenderedImage> renderedImageList = new ArrayList<RenderedImage>(reprojectedProducts.length);
            sourceImageList.add(renderedImageList);
            for (final Product product : reprojectedProducts) {
                renderedImageList.add(createExpressionImage(variable.getExpression(), product));
            }
            if (isUpdateMode()) {
                renderedImageList.add(updateProduct.getBand(variable.getName()).getSourceImage());
            }
        }
        return sourceImageList;
    }

    private boolean isUpdateMode() {
        return updateProduct != null;
    }

    private PlanarImage createExpressionImage(final String expression, Product product) {
        final MultiLevelImage sourceImage = product.getBandAt(0).getSourceImage();
        final ResolutionLevel resolutionLevel = ResolutionLevel.create(sourceImage.getModel(), 0);
        final float fillValue = 0.0f;
        return VirtualBandOpImage.create(expression, ProductData.TYPE_FLOAT32, fillValue, product, resolutionLevel);
    }

    private Product[] createReprojectedProducts() {
        List<Product> reprojProductList = new ArrayList<Product>(sourceProducts.length);
        final HashMap<String, Object> projParameters = createProjectionParameters();
        for (Product sourceProduct : sourceProducts) {
            // todo (mp) 11/20/2009 - no need to check if sourceProducts and named products are initialised correctly
            if (sourceProduct != updateProduct) {
                HashMap<String, Product> projProducts = new HashMap<String, Product>();
                projProducts.put("source", sourceProduct);
                projProducts.put("collocate", targetProduct);
                reprojProductList.add(GPF.createProduct("Reproject", projParameters, projProducts));
            }
        }
        return reprojProductList.toArray(new Product[reprojProductList.size()]);
    }

    private HashMap<String, Object> createProjectionParameters() {
        HashMap<String, Object> projParameters = new HashMap<String, Object>();
        projParameters.put("resamplingName", resamplingName);
        projParameters.put("includeTiePointGrids", true);  // ensure tie-points are reprojected
        if (orthorectify) {
            projParameters.put("orthorectify", orthorectify);
            projParameters.put("elevationModelName", elevationModelName);
        }
        return projParameters;
    }

    private Product createTargetProduct() {
        Product product;
        try {
            CoordinateReferenceSystem targetCRS;
            try {
                targetCRS = CRS.parseWKT(crs);
            } catch (FactoryException e) {
                targetCRS = CRS.decode(crs, true);
            }
            Rectangle2D.Double rect = new Rectangle2D.Double();
            rect.setFrameFromDiagonal(westBound, northBound, eastBound, southBound);
            Envelope envelope = CRS.transform(new Envelope2D(DefaultGeographicCRS.WGS84, rect), targetCRS);
            int width = (int) (envelope.getSpan(0) / pixelSizeX);
            int height = (int) (envelope.getSpan(1) / pixelSizeY);
            final AffineTransform mapTransform = new AffineTransform();
            mapTransform.translate(westBound, northBound);
            mapTransform.scale(pixelSizeX, -pixelSizeY);
            mapTransform.translate(-0.5, -0.5);
            CrsGeoCoding geoCoding = new CrsGeoCoding(targetCRS, new Rectangle(0, 0, width, height),
                                                      mapTransform);
            product = new Product("mosaic", "BEAM_MOSAIC", width, height);
            product.setGeoCoding(geoCoding);
            Dimension tileSize = JAIUtils.computePreferredTileSize(width, height, 1);
            product.setPreferredTileSize(tileSize);
            addTargetBands(product);
        } catch (Exception e) {
            throw new OperatorException(e);
        }

        return product;
    }

    private void addTargetBands(Product product) {
        for (Variable outputVariable : variables) {
            Band band = product.addBand(outputVariable.getName(), ProductData.TYPE_FLOAT32);
            band.setDescription(outputVariable.getExpression());
            final String countBandName = getCountBandName(outputVariable);
            band.setValidPixelExpression(String.format("%s > 0", countBandName));

            Band countBand = product.addBand(countBandName, ProductData.TYPE_INT32);
            countBand.setDescription(String.format("Count of %s", outputVariable.getName()));
        }

        if (conditions != null) {
            for (Condition condition : conditions) {
                if (condition.isOutput()) {
                    Band band = product.addBand(condition.getName(), ProductData.TYPE_INT32);
                    band.setDescription(condition.getExpression());
                }
            }
        }
    }


    public static class Variable {

        @Parameter(description = "The name of the variable.")
        String name;
        @Parameter(description = "The expression of the variable.")
        String expression;

        public Variable() {
        }

        public Variable(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }
    }

    public static class Condition {

        @Parameter(description = "The name of the condition.")
        String name;
        @Parameter(description = "The expression of the condition.")
        String expression;
        @Parameter(description = "Whether the result of the condition shall be written.")
        boolean output;

        public Condition() {
        }

        public Condition(String name, String expression, boolean output) {
            this.name = name;
            this.expression = expression;
            this.output = output;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getExpression() {
            return expression;
        }

        public void setExpression(String expression) {
            this.expression = expression;
        }

        public boolean isOutput() {
            return output;
        }

        public void setOutput(boolean output) {
            this.output = output;
        }
    }

    public static Map<String, Object> getOperatorParameters(Product product) throws OperatorException {
        final MetadataElement graphElement = product.getMetadataRoot().getElement("Processing_Graph");
        if (graphElement == null) {
            throw new OperatorException("Product has no metadata element named 'Processing_Graph'");
        }
        final String operatorAlias = "Mosaic";
        final Map<String, Object> parameters = new HashMap<String, Object>();
        boolean operatorFound = false;
        for (MetadataElement nodeElement : graphElement.getElements()) {
            if (operatorAlias.equals(nodeElement.getAttributeString("operator"))) {
                operatorFound = true;
                collectParameters(MosaicOp.class, nodeElement.getElement("parameters"), parameters);
            }
        }
        if (!operatorFound) {
            throw new OperatorException("No metadata found for operator '" + operatorAlias + "'");
        }
        return parameters;
    }

    private static void collectParameters(Class<?> operatorClass, MetadataElement parentElement,
                                          Map<String, Object> parameters) {
        for (Field field : operatorClass.getDeclaredFields()) {
            final Parameter annotation = field.getAnnotation(Parameter.class);
            if (annotation != null) {
                final Class<?> fieldType = field.getType();
                if (fieldType.isArray()) {
                    initArrayParameter(parentElement, field, parameters);
                } else {
                    initParameter(parentElement, field, parameters);
                }
            }
        }
    }

    private static void initParameter(MetadataElement parentElement, Field field,
                                      Map<String, Object> parameters) throws
                                                                      OperatorException {
        Parameter annotation = field.getAnnotation(Parameter.class);
        String name = annotation.alias();
        if (name.isEmpty()) {
            name = field.getName();
        }
        try {
            if (parentElement.containsAttribute(name)) {
                final Converter converter = getConverter(field.getType(), annotation);
                final String parameterText = parentElement.getAttributeString(name);
                final Object value = converter.parse(parameterText);
                parameters.put(name, value);
            } else {
                final MetadataElement element = parentElement.getElement(name);
                if (element != null) {
                    final Object obj = field.getType().newInstance();
                    final HashMap<String, Object> objParams = new HashMap<String, Object>();
                    collectParameters(obj.getClass(), element, objParams);
                    initObject(objParams, obj);
                    parameters.put(name, obj);
                }
            }
        } catch (Exception e) {
            throw new OperatorException(String.format("Cannot initialise operator parameter '%s'", name), e);
        }
    }

    private static void initArrayParameter(MetadataElement parentElement, Field field,
                                           Map<String, Object> parameters) throws OperatorException {
        String name = field.getAnnotation(Parameter.class).alias();
        if (name.isEmpty()) {
            name = field.getName();
        }
        final MetadataElement element = parentElement.getElement(name);
        try {
            if (element != null) {
                final MetadataElement[] elements = element.getElements();
                final Class<?> componentType = field.getType().getComponentType();
                final Object array = Array.newInstance(componentType, elements.length);
                for (int i = 0; i < elements.length; i++) {
                    MetadataElement arrayElement = elements[i];
                    final Object componentInstance = componentType.newInstance();
                    final HashMap<String, Object> objParams = new HashMap<String, Object>();
                    collectParameters(componentInstance.getClass(), arrayElement, objParams);
                    initObject(objParams, componentInstance);
                    Array.set(array, i, componentInstance);
                }
                parameters.put(name, array);
            }
        } catch (Exception e) {
            throw new OperatorException(String.format("Cannot initialise operator parameter '%s'", name), e);
        }
    }

    private static void initObject(Map<String, Object> params, Object object) {
        for (Field field : object.getClass().getDeclaredFields()) {
            final Parameter annotation = field.getAnnotation(Parameter.class);
            if (annotation != null) {
                String name = annotation.alias();
                if (name.isEmpty()) {
                    name = field.getName();
                }
                try {
                    field.set(object, params.get(name));
                } catch (Exception e) {
                    final String msg = String.format("Cannot initialise operator parameter '%s'", name);
                    throw new OperatorException(msg, e);
                }
            }
        }
    }

    private static Converter<?> getConverter(Class<?> type, Parameter parameter) throws OperatorException {
        final Class<? extends Converter> converter = parameter.converter();
        if (converter == Converter.class) {
            return ConverterRegistry.getInstance().getConverter(type);
        } else {
            try {
                return converter.newInstance();
            } catch (Exception e) {
                final String message = String.format("Cannot find converter for  type '%s'", type);
                throw new OperatorException(message, e);
            }
        }
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(MosaicOp.class);
        }
    }
}