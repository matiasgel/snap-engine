package com.bc.ceres.glayer;

import com.bc.ceres.binding.ValidationException;
import com.bc.ceres.binding.ValueContainer;
import static org.junit.Assert.*;
import org.junit.Test;

public class LayerTest {

    @Test
    public void testType() {
        Layer layer1 = new CollectionLayer();
        Layer layer2 = new CollectionLayer();

        assertNotNull(layer1.getLayerType());
        assertSame(layer1.getLayerType(), layer2.getLayerType());
        assertSame(layer1.getLayerType(), LayerType.getLayerType(CollectionLayer.Type.class));
    }

    @Test
    public void testDefaults() throws ValidationException {
        Layer layer = new CollectionLayer();
        LayerType layerType = layer.getLayerType();
        assertNotNull(layerType);
        assertTrue(layerType.createLayer(null, new ValueContainer()) != null);
        assertTrue(layerType.isValidFor(null));
        assertNotNull(layer.getId());
        assertEquals("Collection Layer", layer.getName());
        assertEquals(true, layer.isVisible());
        assertEquals(0.0, layer.getTransparency(), 1.0e-10);
        assertEquals(Composite.SRC_OVER, layer.getComposite());

        assertNull(layer.getModelBounds());
    }

    @Test
    public void testPropertyAccess() {
        Layer layer = new CollectionLayer();

        assertEquals(true, layer.isVisible());
        layer.setVisible(false);
        assertEquals(false, layer.isVisible());
        layer.setVisible(true);
        assertEquals(true, layer.isVisible());

        assertEquals("Collection Layer", layer.getName());
        layer.setName("Grid");
        assertEquals("Grid", layer.getName());
        layer.setName("Earth grid");
        assertEquals("Earth grid", layer.getName());
        try {
            layer.setName(null);
            fail("NullPointerException expected");
        } catch (NullPointerException ignored) {
            // expected
        }

        assertEquals(0.0, layer.getTransparency(), 1.0e-10);
        layer.setTransparency(0.1);
        assertEquals(0.1, layer.getTransparency(), 1.0e-10);
        layer.setTransparency(1.0);
        assertEquals(1.0, layer.getTransparency(), 1.0e-10);

        assertEquals(Composite.SRC_OVER, layer.getComposite());
        layer.setComposite(Composite.DST_OUT);
        assertEquals(Composite.DST_OUT, layer.getComposite());
    }

    @Test
    public void testPropertyChangeNotification() {
        final Layer layer = new CollectionLayer();
        final TracingLayerListener ll = new TracingLayerListener();
        layer.addListener(ll);

        layer.setName("Grid");
        layer.setVisible(false);
        layer.setTransparency(0.5);
        assertEquals("name;visible;transparency;", ll.trace);

        layer.setName("Grid");
        layer.setVisible(false);
        layer.setTransparency(0.0);
        assertEquals("name;visible;transparency;transparency;", ll.trace);

        layer.setTransparency(0.0);
        layer.setVisible(true);
        layer.setName("Raster");
        assertEquals("name;visible;transparency;transparency;visible;name;", ll.trace);

        layer.setComposite(Composite.DST_IN);
        assertEquals("name;visible;transparency;transparency;visible;name;composite;", ll.trace);

        ll.trace = "";
        layer.removeListener(ll);

        layer.setTransparency(0.25);
        layer.setVisible(false);
        layer.setName("Graticule");
        layer.setComposite(Composite.SRC_OUT);
        assertEquals("", ll.trace);
    }

}