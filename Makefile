JAVAC  ?= javac
JAVA   ?= java
JFLAGS ?= -d build -sourcepath src

SOURCES := $(wildcard src/*.java)

.PHONY: all jar gui-jar run play analyse annotate tune bench gui icon jre appdir appimage clean dist-clean

all: build/.compiled

build/.compiled: $(SOURCES)
	@mkdir -p build
	$(JAVAC) $(JFLAGS) $(SOURCES)
	@touch $@

jar: all
	cd build && jar cfe ../breakthrough.jar Main *.class

# JAR with Gui as the main class — used by the AppImage launcher.
gui-jar: all
	cd build && jar cfe ../breakthrough-gui.jar Gui *.class

run: all
	$(JAVA) -cp build Main $(ARGS)

# convenience: make play, make analyse, make annotate
play: all
	$(JAVA) -cp build Main play $(ARGS)

analyse: all
	$(JAVA) -cp build Main analyse $(ARGS)

annotate: all
	$(JAVA) -cp build Main annotate $(ARGS)

tune: all
	$(JAVA) -cp build Tuner $(ARGS)

bench: all
	$(JAVA) -cp build Main benchmark $(ARGS)

gui: all
	$(JAVA) -cp build Gui

# ----- AppImage build -----
#
# Produces a single self-contained Breakthrough-GUI-x86_64.AppImage. Bundles
# a jlink-trimmed JRE so the user doesn't need Java installed. Requires
# jlink (ships with JDK 21) and downloads appimagetool on first run.
#
# Usage: make appimage
# Output: ./Breakthrough-GUI-x86_64.AppImage

APPIMAGE         := Breakthrough-GUI-x86_64.AppImage
APPDIR           := AppDir
APPIMAGETOOL     := tools/appimagetool-x86_64.AppImage
APPIMAGETOOL_URL := https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage

# Generate the app icon. Reuses our own BoardPanel renderer for the artwork.
icon: all
	$(JAVA) -cp build IconGen $(APPDIR)/breakthrough-gui.png

# Build a minimal JRE containing only the modules our app actually uses.
# jdeps reports java.base and java.desktop; we include java.logging and
# java.xml as a safety margin for Swing's internal needs (it occasionally
# reaches into them via reflection at runtime).
jre:
	@rm -rf $(APPDIR)/usr/lib/jre
	@mkdir -p $(APPDIR)/usr/lib
	jlink \
	    --add-modules java.base,java.desktop,java.logging,java.xml \
	    --strip-debug \
	    --compress=zip-6 \
	    --no-header-files \
	    --no-man-pages \
	    --output $(APPDIR)/usr/lib/jre

# Lay out the AppDir: jar, JRE, AppRun, .desktop, icon.
appdir: gui-jar jre icon
	@mkdir -p $(APPDIR)/usr/lib
	cp breakthrough-gui.jar $(APPDIR)/usr/lib/
	cp packaging/AppRun $(APPDIR)/AppRun
	chmod +x $(APPDIR)/AppRun
	cp packaging/breakthrough-gui.desktop $(APPDIR)/breakthrough-gui.desktop
	# appimagetool also wants the icon as .DirIcon at the AppDir root.
	cp $(APPDIR)/breakthrough-gui.png $(APPDIR)/.DirIcon
	# usr/share/{applications,icons} are conventions; some launchers
	# look there when the AppImage is installed system-wide.
	@mkdir -p $(APPDIR)/usr/share/applications $(APPDIR)/usr/share/icons/hicolor/256x256/apps
	cp packaging/breakthrough-gui.desktop $(APPDIR)/usr/share/applications/
	cp $(APPDIR)/breakthrough-gui.png      $(APPDIR)/usr/share/icons/hicolor/256x256/apps/

# Download appimagetool if we don't have it yet.
$(APPIMAGETOOL):
	@mkdir -p tools
	@echo "Downloading appimagetool..."
	@if command -v curl >/dev/null 2>&1; then \
	    curl -fL -o $@ $(APPIMAGETOOL_URL) || { echo "Download failed."; rm -f $@; exit 1; }; \
	elif command -v wget >/dev/null 2>&1; then \
	    wget -O $@ $(APPIMAGETOOL_URL) || { echo "Download failed."; rm -f $@; exit 1; }; \
	else \
	    echo "Need curl or wget to download appimagetool" >&2; exit 1; \
	fi
	# Sanity check: AppImages start with ELF magic (0x7F 'E' 'L' 'F').
	# Catch HTML error pages or empty files before trying to chmod+execute.
	# Use od for the binary read — grep on binary input is unreliable.
	@magic=$$(od -An -N4 -tx1 $@ | tr -d ' '); \
	 if [ "$$magic" != "7f454c46" ]; then \
	    echo "Downloaded file is not an ELF executable."; \
	    echo "First bytes (hex): $$magic"; \
	    echo "(Probably an HTML error page or download block.)"; \
	    rm -f $@; exit 1; \
	 fi
	chmod +x $@

# Final step: pack the AppDir into a single AppImage file.
appimage: appdir $(APPIMAGETOOL)
	ARCH=x86_64 $(APPIMAGETOOL) $(APPDIR) $(APPIMAGE)
	@echo
	@echo "Built: $(APPIMAGE)"
	@echo "Run with: ./$(APPIMAGE)"

clean:
	rm -rf build breakthrough.jar breakthrough-gui.jar $(APPDIR) $(APPIMAGE)

# Also wipes the downloaded appimagetool.
dist-clean: clean
	rm -rf tools
