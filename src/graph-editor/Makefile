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

CODE        = tme-graph-editor
NAME        = tme-graph-editor
VERSION     = 2.5
PKGNAME     = ${NAME}-${VERSION}

BINPATH     = bin
BUILDPATH   = build
LIBPATH     = lib
CONFPATH    = conf
WEBPATH		= web
RPMPATH     = $(BUILDPATH)/$(PKGNAME)
INSTALLPATH	?= /opt/trend/tme
BUILD_PREFIX    ?=
SHELL       = /bin/bash

SRCDIR      = src

DATE_TAG        = $(shell date -u "+%Y%m%dZ")

DEBBUILD_DIR   := $(TOP_OUTPUT_DIR)/deb/$(PKGNAME)-$(DATE_TAG)_all

# ----------------------------------------------------------------------------
# Main targets
# ----------------------------------------------------------------------------

.PHONY: default
default: 
	@ant

.PHONY: install
install: default
	mkdir -p $(BUILD_PREFIX)/etc/init.d
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(CONFPATH)
	mkdir -p $(BUILD_PREFIX)/$(INSTALLPATH)/$(WEBPATH)
	cp -f $(BINPATH)/tme-graph-editor $(BUILD_PREFIX)/etc/init.d
	cp -f $(BINPATH)/graph-editor.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -f $(BINPATH)/install_tme-graph-editor.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -f $(BINPATH)/remove_tme-graph-editor.sh $(BUILD_PREFIX)/$(INSTALLPATH)/$(BINPATH)
	cp -f $(BUILDPATH)/*.jar $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)
	cp -rf $(LIBPATH)/* $(BUILD_PREFIX)/$(INSTALLPATH)/$(LIBPATH)
	cp -rf $(CONFPATH)/* $(BUILD_PREFIX)/$(INSTALLPATH)/$(CONFPATH)
	cp -rf $(WEBPATH)/* $(BUILD_PREFIX)/$(INSTALLPATH)/$(WEBPATH)

.PHONY: deb
deb:
	rm -rf $(DEBBUILD_DIR)
	BUILD_PREFIX=$(DEBBUILD_DIR) make install
	mkdir -p $(DEBBUILD_DIR)/DEBIAN
	cd $(DEBBUILD_DIR) ; find . -type f | sed -e '/DEBIAN\/md5sums/d' | xargs md5sum | sed -e 's/\.\///g' > $(DEBBUILD_DIR)/DEBIAN/md5sums
	sed -e "s/Version:.*/Version: $(VERSION)-$(DATE_TAG)/g" pkg/deb/control > $(DEBBUILD_DIR)/DEBIAN/control
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
	rpmbuild --target noarch --define "version $(VERSION)" --define "release $(DATE_TAG)" --define="_topdir $(RPMBUILD_DIR)" -bb pkg/rpm/tme-graph-editor.spec
	# Copy RPM files to artifacts directory
	mkdir -p $(ARTIFACTS_DIR);
	cp -f $(RPMBUILD_DIR)/RPMS/*/$(NAME)-*.rpm $(ARTIFACTS_DIR)/;

.PHONY: clean
clean:
	@ant clean

