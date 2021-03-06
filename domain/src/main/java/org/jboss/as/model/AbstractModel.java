/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.model;

import org.jboss.staxmapper.XMLExtendedStreamReader;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;

/**
 * A controlled object model which is related to an XML representation.  Such an object model can be serialized to
 * XML or to binary.
 *
 * @param <M> the concrete model type
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public abstract class AbstractModel<M extends AbstractModel<M>> extends AbstractModelRootElement<M> {

    private static final long serialVersionUID = 66064050420378211L;

    /**
     * Construct a new instance.
     *
     * @param elementName the root element name
     */
    protected AbstractModel(final QName elementName) {
        super(elementName);
    }

    /**
     * Construct a new instance.
     *
     * @param reader the reader from which to build this element
     * @throws XMLStreamException if an error occurs
     */
    protected AbstractModel(final XMLExtendedStreamReader reader) throws XMLStreamException {
        super(reader);
    }

    /**
     * Apply an update to this model.
     *
     * @param update the update to apply
     * @param <R> the update's result type
     * @throws UpdateFailedException if an error occurs
     */
    public <R> void update(AbstractModelUpdate<M, R> update) throws UpdateFailedException {
        update.applyUpdate(cast());
    }
}
