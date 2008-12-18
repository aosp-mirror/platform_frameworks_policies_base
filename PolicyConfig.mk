#
# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Make sure our needed product policy is present.
ifeq ($(PRODUCT_POLICY),)
$(error PRODUCT_POLICY MUST be defined.)
else
  ifeq ($(filter $(PRODUCT_POLICY),$(ALL_MODULES)),)
    $(error No module defined for the given PRODUCT_POLICY ($(PRODUCT_POLICY)))
  else
    # The policy MUST specify a module which builds a single jar
    ifneq ($(words $(call module-built-files,$(PRODUCT_POLICY))),1)
      $(error Policy module $(PRODUCT_POLICY) must build a single library jar)
    endif
  endif
endif


# We will always build all the policies. Then, we will copy the jars from
# the product specified policy into the locations where the current module
# is expected to have its jars. Then, the normal module install code will
# take care of things. We make each policy me non-installable, so that only
# the right one gets taken and installed. Having all the policies built
# also allows us to have unittests that refer to specific policies, but not
# necessarily the one used for the current product being built.
# TODO: Is this the best way to do this?

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := android.policy
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE_SUFFIX := $(COMMON_JAVA_PACKAGE_SUFFIX)

LOCAL_BUILT_MODULE_STEM := javalib.jar

# base_rules.mk may use this in unintended ways if a stale value is
# left lying around, so make sure to clear it.
all_res_assets :=

#######################################
include $(BUILD_SYSTEM)/base_rules.mk
#######################################

src_classes_jar := $(call _java-lib-full-classes.jar,$(PRODUCT_POLICY))
tgt_classes_jar := $(call _java-lib-full-classes.jar,$(LOCAL_MODULE))

src_javalib_jar := $(call module-built-files,$(PRODUCT_POLICY))
tgt_javalib_jar := $(LOCAL_BUILT_MODULE)

# make sure that the classes file gets there first since some rules in other
# places assume that the classes.jar for a module exists if the javalib.jar
# file exists.
$(tgt_javalib_jar): $(tgt_classes_jar)

$(tgt_javalib_jar): $(src_javalib_jar) | $(ACP)
	@echo "Copying policy javalib.jar: $@"
	$(copy-file-to-target)

$(tgt_classes_jar): $(src_classes_jar) | $(ACP)
	@echo "Copying policy classes.jar: $@"
	$(copy-file-to-target)

#
# Clean up after ourselves when switching build types
#
.PHONY: policy_installclean
policy_installclean: PRIVATE_CLEAN_DIR := $(call local-intermediates-dir,COMMON)
policy_installclean:
	$(hide) rm -rf $(PRIVATE_CLEAN_DIR)

installclean: policy_installclean
