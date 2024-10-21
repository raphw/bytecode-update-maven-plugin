package codes.rafael.bytecodeupdate;

import org.objectweb.asm.commons.Remapper;

public class PackageNameRemapper extends Remapper {

    private final String oldPackage, newPackage;

    public PackageNameRemapper(String oldPackage, String newPackage) {
        this.oldPackage = oldPackage;
        this.newPackage = newPackage;
    }

    @Override
    public String map(String internalName) {
        if (internalName.startsWith(oldPackage)) {
            return newPackage + internalName.substring(oldPackage.length());
        }
        return internalName;
    }

    public String reverse(String internalName) {
        if (internalName.startsWith(newPackage)) {
            return oldPackage + internalName.substring(newPackage.length());
        }
        return internalName;
    }
}
