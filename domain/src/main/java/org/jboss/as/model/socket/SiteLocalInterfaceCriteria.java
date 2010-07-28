/**
 * 
 */
package org.jboss.as.model.socket;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * {@link InterfaceCriteria} that tests whether a given address is a 
 * {@link InetAddress#isLinkLocalAddress() link-local} address.
 * 
 * @author Brian Stansberry
 */
public class SiteLocalInterfaceCriteria implements InterfaceCriteria {

    public static final SiteLocalInterfaceCriteria INSTANCE = new SiteLocalInterfaceCriteria();
    
    private SiteLocalInterfaceCriteria() {}
    
    /**
     * {@inheritDoc}
     * 
     * @return <code>true</code> if <code>address</code> is  
     *         {@link InetAddress#isLinkLocalAddress() link-local}.
     */
    @Override
    public boolean isAcceptable(NetworkInterface networkInterface, InetAddress address) throws SocketException {
        
        return address.isSiteLocalAddress();
    }
    
    

}