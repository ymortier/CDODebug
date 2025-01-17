package se.solme.debug.cdo;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILogicalStructureType;
import org.eclipse.debug.core.IStatusHandler;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaThread;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.internal.debug.core.JDIDebugPlugin;
import org.eclipse.jdt.internal.debug.core.JavaDebugUtils;
import org.eclipse.jdt.internal.debug.core.logicalstructures.JDIPlaceholderVariable;
import org.eclipse.jdt.internal.debug.core.logicalstructures.LogicalObjectStructureValue;

import se.solme.debug.cdo.internal.Methods;

@SuppressWarnings("restriction")
public class CdoLogicalStructureType implements ILogicalStructureType {

	private final boolean fSubtypes = true;

	/**
	 * Fully qualified type name.
	 */
	private final String fType = "org.eclipse.emf.ecore.impl.MinimalEObjectImpl";

	@Override
	public boolean providesLogicalStructure(final IValue value) {
		if (!(value instanceof IJavaObject)) {
			return false;
		}
		final IJavaObject javaObject = (IJavaObject) value;
		try {
			final IJavaType javaType = javaObject.getJavaType();
			if (javaType instanceof IJavaClassType) {
				final IJavaClassType javaClassType = (IJavaClassType) javaType;
				return this.isMinimalEObject(javaClassType);
			}
		} catch (final DebugException e) {
			JDIDebugPlugin.log(e);
		}

		return false;
	}

	private boolean isMinimalEObject(final IJavaClassType javaClassType) throws DebugException {
		if (this.fType.equals(javaClassType.getName())) {
			return true;
		}
		if (this.fSubtypes) {
			return this.isMinimalEObject(javaClassType.getSuperclass());
		}
		return false;
	}

	@Override
	public IValue getLogicalStructure(final IValue value) throws CoreException {

		System.out.println("Provide structure for: " + value);
		if (!(value instanceof IJavaObject)) {
			return value;
		}

		final IJavaObject javaValue = (IJavaObject) value;
		try {
			final IJavaStackFrame stackFrame = this.getStackFrame(javaValue);
			if (stackFrame == null) {
				return value;
			}
			final IJavaProject project = JavaDebugUtils.resolveJavaProject(stackFrame);
			if (project == null) {
				return value;
			}

			final IJavaThread javaThread = this.getJavaThread(this.getStackFrame(javaValue));
			if (javaThread == null) {
				return value;
			}

			final List<JDIPlaceholderVariable> variables = new ArrayList<>();

			// The "eContainer" implicit EReference.
			final IJavaValue eContainer = javaValue.sendMessage(Methods.EObject_eContainer, Methods.EObject_eContainer_Sign,
					new IJavaValue[0], javaThread, null);
			variables.add(new JDIPlaceholderVariable(Methods.EObject_eContainer, eContainer, javaValue));

			// The EClass of the EObject:
			final IJavaValue eClassValue = javaValue.sendMessage(Methods.EObject_eClass, Methods.EObject_eClass_Sign, new IJavaValue[0],
					javaThread, null);
			if (eClassValue instanceof IJavaObject) {
				// All structural features of the EClass (as an EList):
				final IJavaValue eAllStructuralFeatures = ((IJavaObject) eClassValue).sendMessage(Methods.EClass_getEAllStructuralFeatures,
						Methods.EClass_getEAllStructuralFeatures_Sign, new IJavaValue[0], javaThread, null);
				if (eAllStructuralFeatures instanceof IJavaObject) {
					// Get the structural features as an array:
					final IJavaArray eSFsArray = (IJavaArray) ((IJavaObject) eAllStructuralFeatures).sendMessage(Methods.List_toArray,
							Methods.List_toArray_Sign, new IJavaValue[0], javaThread, null);
					for (final IJavaValue element : eSFsArray.getValues()) {
						// Name of the EStructuralFeature:
						final IJavaValue name = ((IJavaObject) element).sendMessage(Methods.ENamedElement_getName,
								Methods.ENamedElement_getName_Sign, new IJavaValue[0], javaThread, null);

						// Value of the EStructuralFeature:
						final IJavaValue actualAttribValue = javaValue.sendMessage(Methods.EObject_eGet, Methods.EObject_eGet_Sign,
								new IJavaValue[] { element }, javaThread, null);
						// Finally, add as Variable:
						variables.add(new JDIPlaceholderVariable(name.getValueString(), actualAttribValue, javaValue));
					}
				}

			}
			// What we return as "logical children" for the EObject are the
			// variables.
			return new LogicalObjectStructureValue(javaValue, variables.toArray(new JDIPlaceholderVariable[variables.size()]));

		} catch (final CoreException e) {
			if (e.getStatus().getCode() == IJavaThread.ERR_THREAD_NOT_SUSPENDED) {
				throw e;
			}
			JDIDebugPlugin.log(e);
		}
		return value;
	}

	@Override
	public String getDescription(final IValue value) {
		return this.getDescription();
		// if ( value != null ) {
		// try {
		// return "CDO: " + value.getValueString();
		// } catch (DebugException e) {
		// // TODO Auto-generated catch block
		// e.printStackTrace();
		// }
		// }
		// return null;
	}

	@Override
	public String getDescription() {
		return "EObject [logical struct]";
	}

	@Override
	public String getId() {
		return "se.solme.debug.cdo" + this.fType + this.getDescription();
	}

	/**
	 * Return the current stack frame context, or a valid stack frame for the
	 * given value.
	 *
	 * @param value
	 * @return the current stack frame context, or a valid stack frame for the
	 *         given value.
	 * @throws CoreException
	 */
	private IJavaStackFrame getStackFrame(final IValue value) throws CoreException {
		final IStatusHandler handler = getStackFrameProvider();
		if (handler != null) {
			final IJavaStackFrame stackFrame = (IJavaStackFrame) handler.handleStatus(JDIDebugPlugin.STATUS_GET_EVALUATION_FRAME, value);
			if (stackFrame != null) {
				return stackFrame;
			}
		}
		final IDebugTarget target = value.getDebugTarget();
		final IJavaDebugTarget javaTarget = target.getAdapter(IJavaDebugTarget.class);
		if (javaTarget != null) {
			final IThread[] threads = javaTarget.getThreads();
			for (final IThread thread : threads) {
				if (thread.isSuspended()) {
					return (IJavaStackFrame) thread.getTopStackFrame();
				}
			}
		}
		return null;
	}

	private IJavaThread getJavaThread(final IJavaStackFrame stackFrame) {
		return stackFrame != null ? (IJavaThread) stackFrame.getThread() : null;
	}

	private static IStatusHandler fgStackFrameProvider;

	/**
	 * Returns the singleton stackframe provider
	 *
	 * @return the singleton stackframe provider
	 */
	private static IStatusHandler getStackFrameProvider() {
		if (fgStackFrameProvider == null) {
			fgStackFrameProvider = DebugPlugin.getDefault().getStatusHandler(JDIDebugPlugin.STATUS_GET_EVALUATION_FRAME);
		}
		return fgStackFrameProvider;
	}

}
