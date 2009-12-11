package org.esa.beam.visat.actions;

import com.bc.ceres.glayer.Layer;
import com.bc.ceres.glayer.support.LayerUtils;
import com.bc.ceres.swing.figure.AbstractInteractorListener;
import com.bc.ceres.swing.figure.Interactor;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.VectorDataNode;
import org.esa.beam.framework.ui.ModalDialog;
import org.esa.beam.framework.ui.product.ProductSceneView;
import org.esa.beam.framework.ui.product.VectorDataLayer;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.util.HashMap;


public class InsertFigureInteractorHandler extends AbstractInteractorListener {
    @Override
    public boolean canStartInteraction(Interactor interactor, InputEvent inputEvent) {
        ProductSceneView productSceneView = getProductSceneView(inputEvent);
        if (productSceneView == null) {
            return false;
        }

        VectorDataLayer layer = getVectorDataLayer(productSceneView.getProduct(),
                                                   productSceneView.getSelectedLayer());
        boolean validLayerSelected = layer != null;
        if (validLayerSelected) {
            return true;
        }
        Layer collectionLayer = productSceneView.getVectorDataCollectionLayer(true);
        HashMap<String, VectorDataLayer> layers = new HashMap<String, VectorDataLayer>();
        for (Layer layer1 : collectionLayer.getChildren()) {
            VectorDataLayer vectorDataLayer = getVectorDataLayer(productSceneView.getProduct(),
                                                                 layer1);
            if (vectorDataLayer != null) {
                layers.put(vectorDataLayer.getName(), vectorDataLayer);
            }
        }

        VectorDataLayer vectorDataLayer = null;
        if (layers.size() > 0) {
            JList listBox = new JList(layers.keySet().toArray());
            JPanel panel = new JPanel(new BorderLayout(4, 4));
            panel.add(new JLabel("Please select a target layer:"), BorderLayout.NORTH);
            panel.add(new JScrollPane(listBox), BorderLayout.CENTER);
            ModalDialog dialog = new ModalDialog(SwingUtilities.getWindowAncestor(productSceneView),
                                                 "Select Geometry Layer",
                                                 ModalDialog.ID_OK_CANCEL_HELP, "");
            dialog.setContent(panel);
            int i = dialog.show();
            if (i == ModalDialog.ID_OK) {
                String selectedLayerName = (String) listBox.getSelectedValue();
                if (selectedLayerName != null) {
                    vectorDataLayer = layers.get(selectedLayerName);
                }
            } else {
                return false;
            }
        }
        if (vectorDataLayer == null) {
            vectorDataLayer = newLayer(collectionLayer);
        }
        if (vectorDataLayer == null) {
            return false;
        }
        productSceneView.setSelectedLayer(vectorDataLayer);
        return productSceneView.getSelectedLayer() == vectorDataLayer;
    }

    private static VectorDataLayer newLayer(Layer collectionLayer) {
        NewVectorDataAction action = new NewVectorDataAction();
        action.run();
        String value = action.getVectorDataName();
        if (value != null) {
            Layer layer = LayerUtils.getChildLayerByName(collectionLayer, value);
            if (layer instanceof VectorDataLayer) {
                return (VectorDataLayer) layer;
            }
        }
        return null;
    }

    private ProductSceneView getProductSceneView(InputEvent event) {
        ProductSceneView productSceneView = null;
        Component component = event.getComponent();
        while (component != null) {
            if (component instanceof ProductSceneView) {
                productSceneView = (ProductSceneView) component;
                break;
            }
            component = component.getParent();
        }
        return productSceneView;
    }

    private VectorDataLayer getVectorDataLayer(Product product, Layer layer) {
        if (layer instanceof VectorDataLayer) {
            final VectorDataLayer vectorDataLayer = (VectorDataLayer) layer;
            final VectorDataNode vectorDataNode = vectorDataLayer.getVectorData();
            if (!product.isInternalNode(vectorDataNode)) {
                return vectorDataLayer;
            }
        }
        return null;
    }
}
