# ----------------------------------------------------------------------------
# Global/shared directories for all sub modules that will be passed to
# sub-make processes via export.
# ----------------------------------------------------------------------------

export TOP_DIR          = $(abspath .)
export TOP_SRC_DIR      = $(TOP_DIR)/src
export TOP_QA_DIR       = $(TOP_DIR)/QA
export TOP_3RDPARTY_DIR = $(TOP_DIR)/3rd_party
export TOP_OUTPUT_DIR   = $(TOP_DIR)/output

export ARTIFACTS_DIR    = $(TOP_OUTPUT_DIR)/artifacts
export REPORTS_DIR      = $(TOP_OUTPUT_DIR)/reports
export MANUALS_DIR      = $(TOP_OUTPUT_DIR)/manuals
export RPMBUILD_DIR     = $(TOP_OUTPUT_DIR)/rpmbuild
export WORKS_DIR        = $(TOP_OUTPUT_DIR)/works



# ----------------------------------------------------------------------------
# List of sub modules. Sub modules should have gmake(1) makefile contained in
# their sub directory, and should have the following make targets defined:
# 'rpm', 'clean', and 'test'.
# ----------------------------------------------------------------------------

SUB_MODULE_DIRS =# list of sub modules
SUB_MODULE_DIRS += src/common
SUB_MODULE_DIRS += src/broker
SUB_MODULE_DIRS += src/mist
SUB_MODULE_DIRS += src/portal-collector
SUB_MODULE_DIRS += src/graph-editor
SUB_MODULE_DIRS += src/mist-tools
SUB_MODULE_DIRS += src/portal-web
SUB_MODULE_DIRS += src/console


# ----------------------------------------------------------------------------
# Main targets
# ----------------------------------------------------------------------------

.PHONY: all
all: help

.PHONY: help
help:
	@echo "Usage: make [ TARGET ... ]";
	@echo "";
	@echo "TARGET:";
	@echo "";
	@echo "  help   - show this help message";
	@echo "  rpm    - build RPM packages";
	@echo "  build  - build all modules";
	@echo "  clean  - remove all file built/generated";
#	@echo "  test   - run tests with coverage collecting";
	@echo "";
	@echo "Default TARGET is 'help'.";
	@echo "";

.PHONY: rpm
rpm:
	@for d in $(SUB_MODULE_DIRS); do \
		$(MAKE) --print-directory --directory=$$d rpm; \
	done;

.PHONY: deb
deb:
	@for d in $(SUB_MODULE_DIRS); do \
		$(MAKE) --print-directory --directory=$$d deb; \
	done;

.PHONY: clean
clean:
	@for d in $(SUB_MODULE_DIRS); do \
		$(MAKE) --print-directory --directory=$$d clean; \
	done;

.PHONY: build
build:
	@for d in $(SUB_MODULE_DIRS); do \
		$(MAKE) --print-directory --directory=$$d default; \
	done;

