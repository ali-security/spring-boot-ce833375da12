/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.autoconfigure.jms.artemis;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;

import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.CoreQueueConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.artemis.core.server.JournalType;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.system.ApplicationHome;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Configuration used to create the embedded Artemis server.
 *
 * @author Eddú Meléndez
 * @author Stephane Nicoll
 * @author Phillip Webb
 */
class ArtemisEmbeddedConfigurationFactory {

	private static final Log logger = LogFactory.getLog(ArtemisEmbeddedConfigurationFactory.class);

	private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

	private final ArtemisProperties.Embedded properties;

	ArtemisEmbeddedConfigurationFactory(ArtemisProperties properties) {
		this.properties = properties.getEmbedded();
	}

	Configuration createConfiguration() {
		ConfigurationImpl configuration = new ConfigurationImpl();
		configuration.setSecurityEnabled(false);
		configuration.setPersistenceEnabled(this.properties.isPersistent());
		String dataDir = getDataDir();
		configuration.setJournalDirectory(dataDir + "/journal");
		if (this.properties.isPersistent()) {
			configuration.setJournalType(JournalType.NIO);
			configuration.setLargeMessagesDirectory(dataDir + "/largemessages");
			configuration.setBindingsDirectory(dataDir + "/bindings");
			configuration.setPagingDirectory(dataDir + "/paging");
		}
		TransportConfiguration transportConfiguration = new TransportConfiguration(InVMAcceptorFactory.class.getName(),
				this.properties.generateTransportParameters());
		configuration.getAcceptorConfigurations().add(transportConfiguration);
		if (this.properties.isDefaultClusterPassword() && logger.isDebugEnabled()) {
			logger.debug("Using default Artemis cluster password: " + this.properties.getClusterPassword());
		}
		configuration.setClusterPassword(this.properties.getClusterPassword());
		configuration.addAddressConfiguration(createAddressConfiguration("DLQ"));
		configuration.addAddressConfiguration(createAddressConfiguration("ExpiryQueue"));
		configuration.addAddressesSetting("#",
				new AddressSettings().setDeadLetterAddress(SimpleString.toSimpleString("DLQ"))
						.setExpiryAddress(SimpleString.toSimpleString("ExpiryQueue")));
		return configuration;
	}

	private CoreAddressConfiguration createAddressConfiguration(String name) {
		return new CoreAddressConfiguration().setName(name).addRoutingType(RoutingType.ANYCAST).addQueueConfiguration(
				new CoreQueueConfiguration().setName(name).setRoutingType(RoutingType.ANYCAST).setAddress(name));
	}

	private String getDataDir() {
		if (this.properties.getDataDirectory() != null) {
			return this.properties.getDataDirectory();
		}
		Path applicationTemp = createPrivateDirectory(getTempDirectory().resolve(toHexString(generateHash())), false);
		return createPrivateDirectory(applicationTemp.resolve("artemis-data"), true).toAbsolutePath().toString();
	}

	// Inlined hardening of ApplicationTemp (CVE-2026-40973), which ships in spring-boot
	// and is not fixed in the public 2.3.8.RELEASE. Same directory as
	// new ApplicationTemp().getDir("artemis-data").
	private Path getTempDirectory() {
		String property = System.getProperty("java.io.tmpdir");
		Assert.state(StringUtils.hasLength(property), "No 'java.io.tmpdir' property set");
		File file = new File(property);
		Assert.state(file.isDirectory(), () -> "Temp location " + file + " is not a directory");
		return file.toPath();
	}

	private byte[] generateHash() {
		ApplicationHome home = new ApplicationHome();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			update(digest, home.getSource());
			update(digest, home.getDir());
			update(digest, System.getProperty("user.dir"));
			update(digest, System.getProperty("java.home"));
			update(digest, System.getProperty("java.class.path"));
			update(digest, System.getProperty("sun.java.command"));
			update(digest, System.getProperty("sun.boot.class.path"));
			return digest.digest();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void update(MessageDigest digest, Object source) {
		if (source != null) {
			String value = (source instanceof File) ? ((File) source).getAbsolutePath() : source.toString();
			digest.update(value.getBytes());
		}
	}

	private String toHexString(byte[] bytes) {
		char[] hex = new char[bytes.length * 2];
		for (int i = 0; i < bytes.length; i++) {
			int b = bytes[i] & 0xFF;
			hex[i * 2] = HEX_CHARS[b >>> 4];
			hex[i * 2 + 1] = HEX_CHARS[b & 0x0F];
		}
		return new String(hex);
	}

	// An existing directory is accepted only if it is a real directory (not a symlink)
	// owned by the current user and not writable by others (nor by the group when
	// strict). Unlike the upstream ApplicationTemp it does not require exactly
	// rwx------, because the public spring-boot ApplicationTemp in the same application
	// may already have created the parent with the umask's default permissions.
	private Path createPrivateDirectory(Path path, boolean strict) {
		try {
			FileSystem fileSystem = path.getFileSystem();
			boolean posix = fileSystem.supportedFileAttributeViews().contains("posix");
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				FileAttribute<?>[] attributes = posix
						? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS) }
						: new FileAttribute<?>[0];
				Files.createDirectory(path, attributes);
				return path;
			}
			Assert.state(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS),
					() -> "'" + path + "' already exists but it is not a directory");
			UserPrincipal owner = getCurrentUser();
			if (posix) {
				PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				Set<PosixFilePermission> permissions = attributes.permissions();
				boolean writableByOthers = permissions.contains(PosixFilePermission.OTHERS_WRITE)
						|| (strict && permissions.contains(PosixFilePermission.GROUP_WRITE));
				Assert.state(!writableByOthers, () -> "Existing directory '" + path + "' is writable by other users");
				assertOwner(owner, attributes.owner(), path);
			}
			else if (owner != null) {
				try {
					assertOwner(owner, Files.getOwner(path, LinkOption.NOFOLLOW_LINKS), path);
				}
				catch (UnsupportedOperationException ex) {
					// Ownership check not supported
				}
			}
			return path;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to create Artemis data directory " + path, ex);
		}
	}

	private void assertOwner(UserPrincipal expected, UserPrincipal actual, Path path) {
		Assert.state(expected == null || expected.equals(actual),
				() -> "Existing directory '" + path + "' is not owned by " + expected.getName());
	}

	private UserPrincipal getCurrentUser() throws IOException {
		Path tempFile = Files.createTempFile("artemis-data", "-owner");
		try {
			return Files.getOwner(tempFile);
		}
		catch (UnsupportedOperationException ex) {
			return null;
		}
		finally {
			Files.delete(tempFile);
		}
	}

}
