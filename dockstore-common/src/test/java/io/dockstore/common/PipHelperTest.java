package io.dockstore.common;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author gluu
 * @since 14/08/18
 */
public class PipHelperTest {
    @Test
    public void convertSemVerToAvailableVersion() {
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion(PipHelper.DEV_SEM_VER));
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion(null));
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion("9000.9000.9000"));
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion("1.10.0-snapshot"));
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion("1.10.1"));
        Assert.assertEquals("1.10.0", PipHelper.convertSemVerToAvailableVersion("1.10.0"));
        Assert.assertEquals("1.7.0", PipHelper.convertSemVerToAvailableVersion("1.9.0"));
        Assert.assertEquals("1.7.0", PipHelper.convertSemVerToAvailableVersion("1.8.0"));
        Assert.assertEquals("1.7.0", PipHelper.convertSemVerToAvailableVersion("1.7.0-snapshot"));
        Assert.assertEquals("1.7.0", PipHelper.convertSemVerToAvailableVersion("1.7.0"));
        Assert.assertEquals("1.6.0", PipHelper.convertSemVerToAvailableVersion("1.6.0-snapshot"));
        Assert.assertEquals("1.6.0", PipHelper.convertSemVerToAvailableVersion("1.6.0"));
        Assert.assertEquals("1.5.0", PipHelper.convertSemVerToAvailableVersion("1.5.0-snapshot"));
        Assert.assertEquals("1.5.0", PipHelper.convertSemVerToAvailableVersion("1.5.0"));
        Assert.assertEquals("1.4.0", PipHelper.convertSemVerToAvailableVersion("1.4.0-snapshot"));
        Assert.assertEquals("1.4.0", PipHelper.convertSemVerToAvailableVersion("1.4.0"));
        Assert.assertEquals("1.4.0", PipHelper.convertSemVerToAvailableVersion("1.3.0-snapshot"));
        Assert.assertEquals("1.4.0", PipHelper.convertSemVerToAvailableVersion("1.3.0"));
    }
}
