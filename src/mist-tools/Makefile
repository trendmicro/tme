# ----------------------------------------------------------------------------
# Global/shared directories that should inherit from top-level makefile.
# In case nothing is inherit from, we still have our defaults that follow O2
# convention.
# ----------------------------------------------------------------------------

TOP_DIR        ?= $(abspath ../..)
TOP_OUTPUT_DIR ?= $(TOP_DIR)/output
ARTIFACTS_DIR  ?= $(TOP_OUTPUT_DIR)/artifacts
RPMBUILD_DIR   ?= $(TOP_OUTPUT_DIR)/rpmbuild
WORKS_DIR      ?= $(TOP_OUTPUT_DIR)/works


# ----------------------------------------------------------------------------
# Module specific variables
# ----------------------------------------------------------------------------

CODE        = tme-mist-tools
NAME        = tme-mist-tools
VERSION     = 2.5
PKGNAME     = ${NAME}-${VERSION}

BINPATH     = $(WORKS_DIR)/mist-tools
BUILDPATH   = build
LIBPATH     = lib
CONFPATH    = conf
WEBPATH		= web
RPMPATH     = $(BUILDPATH)/$(PKGNAME)
INSTALLPATH	= /opt/trend/tme
BUILD_PREFIX    ?=
SHELL       = /bin/bash

SRCDIR      = src

DATE_TAG        = $(shell date -u "+%Y%m%dZ")

export ARCH=$(shell uname -m)
ifeq ($(ARCH), x86_64)
	DEB_ARCH = amd64
endif
ifeq ($(ARCH), i686)
	DEB_ARCH = i386
else
	DEB_ARCH = $(ARCH)
endif

DEBBUILD_DIR   := $(TOP_OUTPUT_DIR)/deb/$(PKGNAME)-$(DATE_TAG)_$(DEB_ARCH)

# ----------------------------------------------------------------------------
# Main targets
# ----------------------------------------------------------------------------

.PHONY: default
default: $(WORKS_DIR)/protobuf-2.1.0/src/protoc
	mkdir -p $(BINPATH)
	cd $(BINPATH) ; cmake $(TOP_DIR)/src ; make

$(WORKS_DIR)/protobuf-2.1.0/src/protoc:
	cd $(TOP_DIR)/3rd_party ; make protobuf

.PHONY: install
install: default
	mkdir -p $(BUILD_PREFIX)/usr/bin
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)
	cp -f $(BINPATH)/mist-tools/mist-session $(BUILD_PREFIX)/usr/bin
	cp -f $(BINPATH)/mist-tools/mist-sink $(BUILD_PREFIX)/usr/bin
	cp -f $(BINPATH)/mist-tools/mist-source $(BUILD_PREFIX)/usr/bin
	cp -f $(BINPATH)/mist-tools/mist-encode $(BUILD_PREFIX)/usr/bin
	cp -f $(BINPATH)/mist-tools/mist-decode $(BUILD_PREFIX)/usr/bin
	cp -f $(WORKS_DIR)/protobuf-2.1.0/src/.libs/libprotobuf.so.4 $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH) || true
	cp -f $(WORKS_DIR)/protobuf-2.1.0/src/.libs/libprotobuf.4.dylib $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH) || true

.PHONY: deb
deb:
	rm -rf $(DEBBUILD_DIR)
	BUILD_PREFIX=$(DEBBUILD_DIR) make install
	mkdir -p $(DEBBUILD_DIR)/DEBIAN
	cd $(DEBBUILD_DIR) ; find . -type f | sed -e '/DEBIAN\/md5sums/d' | xargs md5sum | sed -e 's/\.\///g' > $(DEBBUILD_DIR)/DEBIAN/md5sums
	sed -e "s/Version:.*/Version: $(VERSION)-$(DATE_TAG)/g" pkg/deb/control > $(DEBBUILD_DIR)/DEBIAN/control
	sed -i -e "s/Architecture:.*/Architecture: $(DEB_ARCH)/g" $(DEBBUILD_DIR)/DEBIAN/control
	cp -f pkg/deb/postinst $(DEBBUILD_DIR)/DEBIAN/
	cp -f pkg/deb/prerm $(DEBBUILD_DIR)/DEBIAN/
	dpkg-deb --build $(DEBBUILD_DIR)
	mkdir -p $(ARTIFACTS_DIR);
	cp -f $(DEBBUILD_DIR)/../*.deb $(ARTIFACTS_DIR)

.PHONY: rebuild
rebuild: clean default

.PHONY: release
release: rebuild upload

.PHONY: rpm
rpm: default
	mkdir -p $(RPMBUILD_DIR)/{RPMS,SOURCES,BUILD,SPECS,SRPMS}
	# Create source tarball
	rm -rf $(RPMPATH)
	BUILD_PREFIX=$(RPMPATH) make install
	cd $(BUILDPATH); tar cfz $(PKGNAME).tar.gz $(PKGNAME)
	mv $(BUILDPATH)/$(PKGNAME).tar.gz $(RPMBUILD_DIR)/SOURCES
	rm -rf $(RPMPATH) 
	# Create SPEC file with changelog, and build RPM files.
	rpmbuild --target $(ARCH) --define "version $(VERSION)" --define "release $(DATE_TAG)" --define="_topdir $(RPMBUILD_DIR)" -bb pkg/rpm/tme-mist-tools.spec
	# Copy RPM files to artifacts directory
	mkdir -p $(ARTIFACTS_DIR);
	cp -f $(RPMBUILD_DIR)/RPMS/*/$(NAME)-*.rpm $(ARTIFACTS_DIR)/;

.PHONY: clean
clean:
	rm -rf $(BINPATH)

