# ----------------------------------------------------------------------------
# Global/shared directories that should inherit from top-level makefile.
# In case nothing is inherit from, we still have our defaults that follow O2
# convention.
# ----------------------------------------------------------------------------

TOP_DIR        ?= $(abspath ../..)
TOP_OUTPUT_DIR ?= $(TOP_DIR)/output
ARTIFACTS_DIR  ?= $(TOP_OUTPUT_DIR)/artifacts
RPMBUILD_DIR   ?= $(TOP_OUTPUT_DIR)/rpmbuild


# ----------------------------------------------------------------------------
# Module specific variables
# ----------------------------------------------------------------------------

CODE        = tme-portal-web
NAME        = tme-portal-web
VERSION     = 2.5
PKGNAME     = ${NAME}-${VERSION}

BINPATH     = bin
BUILDPATH   = build
LIBPATH     = lib
CONFPATH    = conf
WEBPATH		= web
RPMPATH     = $(BUILDPATH)/$(PKGNAME)
INSTALLPATH	= /opt/trend/tme
BUILD_PREFIX    ?=
SHELL       = /bin/bash

SRCDIR      = src

DATE_TAG    =	$(shell date -u "+%Y%m%dZ")

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
default:
	cd src ; bundle install --local --path=lib

.PHONY: install
install: default
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(CONFPATH)
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)/portal-web
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)/portal-web/public/images
	mkdir -p $(BUILD_PREFIX)/etc/init.d
	cp -rf $(SRCDIR)/* $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)/portal-web
	cp -rf $(CONFPATH)/* $(BUILD_PREFIX)/$(INSTALLPATH)/$(CONFPATH)
	cp -rf $(BINPATH)/portal-web.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -rf $(BINPATH)/install_tme-portal-web.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -rf $(BINPATH)/remove_tme-portal-web.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -rf $(BINPATH)/tme-portal-web $(BUILD_PREFIX)/etc/init.d
	rm -rf $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)/portal-web/vendor
	rm -rf $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)/portal-web/tests

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
	rpmbuild --target $(ARCH) --define "version $(VERSION)" --define "release $(DATE_TAG)" --define="_topdir $(RPMBUILD_DIR)" -bb pkg/rpm/tme-portal-web.spec
	# Copy RPM files to artifacts directory
	mkdir -p $(ARTIFACTS_DIR);
	cp -f $(RPMBUILD_DIR)/RPMS/*/$(NAME)-*.rpm $(ARTIFACTS_DIR)/;

.PHONY: clean
clean:
	rm -rf src/lib/ruby
	rm -rf src/.bundle
	rm -rf build
	rm -rf src/public/images/*
