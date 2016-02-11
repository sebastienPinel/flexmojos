package net.flexmojos.oss.compatibilitykit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.component.annotations.Component;

/**
 * Reflection helper which handles incompatible changes in maven core between maven 3.0.x and maven 3.1
 */
@Component( role = MavenCompatiblityHelper.class )
public class MavenCompatiblityHelper
{

    private Method getRepositorySessionMethod;

    private Method setRepositorySessionMethod;

    public MavenCompatiblityHelper()
    {
        setRepositorySessionMethod = getMethod( ProjectBuildingRequest.class, "setRepositorySession" );
        getRepositorySessionMethod = getMethod( MavenSession.class, "getRepositorySession" );
    }

    @SuppressWarnings("rawtypes")
    private static Method getMethod( Class clazz, String methodName )
    {
        for ( Method method : clazz.getMethods() )
        {
            if ( methodName.equals(method.getName()) )
            {
                return method;
            }
        }
        throw new RuntimeException( "Method '" + methodName + "' not found for class " + clazz.getName() );
    }

    /**
     * Equivalent to {@link MavenPluginManager#getPluginDescriptor(Plugin, project.getRemotePluginRepositories(),
     * session.getRepositorySession())}. The types RemoteRepository and RepositorySystemSession from aether are changed
     * incompatibly in maven 3.1 so we invoke MavenPluginManager#getPluginDescriptor reflectively. See maven issue <a
     * href="http://jira.codehaus.org/browse/MNG-5354">MNG-5354</a>.
     * @throws InvocationTargetException
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    public ProjectBuildingRequest getProjectBuildingRequest( MavenSession session, ArtifactRepository localRepository,
        List<ArtifactRepository> remoteRepositories )
    {
        try
        {
            ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
            request.setLocalRepository( localRepository );
            request.setRemoteRepositories( remoteRepositories );
            request.setResolveDependencies( true );
            setRepositorySessionMethod.invoke( request, getRepositorySessionMethod.invoke(session) );
            return request;
        }
        catch ( IllegalAccessException e )
        {
            throw new RuntimeException( e );
        }
        catch ( InvocationTargetException e )
        {
            Throwable target = e.getTargetException();
            if ( target instanceof RuntimeException )
            {
                throw ( RuntimeException ) target;
            }
            throw new RuntimeException( e );
        }
    }
}