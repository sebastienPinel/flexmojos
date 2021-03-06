/**
 * Flexmojos is a set of maven goals to allow maven users to compile, optimize and test Flex SWF, Flex SWC, Air SWF and Air SWC.
 * Copyright (C) 2008-2012  Marvin Froeder <marvin@flexmojos.net>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.flexmojos.oss.plugin.war;

import static net.flexmojos.oss.util.PathUtil.file;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import net.flexmojos.oss.compatibilitykit.MavenCompatiblityHelper;
import net.flexmojos.oss.plugin.AbstractMavenMojo;
import net.flexmojos.oss.plugin.common.FlexExtension;
import net.flexmojos.oss.plugin.common.FlexScopes;
import net.flexmojos.oss.plugin.compiler.attributes.MavenRuntimeException;
import net.flexmojos.oss.plugin.utilities.CompileConfigurationLoader;
import net.flexmojos.oss.plugin.utilities.MavenUtils;

/**
 * Goal to copy flex artifacts into war projects.
 * 
 * @author Marvin Herman Froeder (velo.br@gmail.com)
 * @since 3.0
 * @goal copy-flex-resources
 * @phase process-resources
 * @requiresDependencyResolution
 */
public class CopyMojo
    extends AbstractMavenMojo
    implements FlexScopes, FlexExtension
{

    /**
     * @parameter default-value="true"
     */
    private boolean copyRSL;

    /**
     * @parameter default-value="true"
     */
    private boolean copyRuntimeLocales;

    /**
     * @component
     */
    private ProjectBuilder projectBuilder;

    /**
     * Skip mojo execution
     * 
     * @parameter default-value="false" expression="${flexmojos.copy.skip}"
     */
    private boolean skip;

    /**
     * When true will strip artifact and version information from the built MXML module artifact.
     * 
     * @parameter default-value="false"
     */
    private boolean stripModuleArtifactInfo;

    /**
     * Strip artifact version during copy
     * 
     * @parameter default-value="false"
     */
    private boolean stripVersion;

    /**
     * Use final name if/when available
     * 
     * @parameter default-value="true"
     */
    private boolean useFinalName;

    /**
     * The directory where the webapp is built.
     * 
     * @parameter expression="${project.build.directory}/${project.build.finalName}"
     * @required
     */
    private File webappDirectory;

    /**
     * Compatibility component to help with aether api incompatibility between maven 3.0 and 3.1.
     * @component
     * @required
     */
    private MavenCompatiblityHelper compatibilityHelper;
    
    /**
     * @component
     * @readonly
     */
    protected ArchiverManager archiverManager;
    
    private void copy( File sourceFile, File destFile )
        throws MojoExecutionException
    {
        try
        {
            FileUtils.copyFile( sourceFile, destFile );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to copy " + sourceFile, e );
        }
    }

    public void fmExecute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( skip )
        {
            getLog().info( "Skipping copy-mojo execution" );
            return;
        }

        String packaging = project.getPackaging();

        if ( "war".equals( packaging ) )
        {
            webappDirectory.mkdirs();

            List<Artifact> swfDependencies = getSwfArtifacts();

            for ( Artifact artifact : swfDependencies )
            {
                File sourceFile = artifact.getFile();
                File destFile = getDestinationFile( artifact );
                
                if (sourceFile.isDirectory())
                {
                    MavenProject projectDependency = getProject( artifact );
                    List<Plugin> buildPlugins = projectDependency.getBuildPlugins();
                    for ( Plugin plugin : buildPlugins )
                    {
                        if ( "flexmojos-maven-plugin".equals( plugin.getArtifactId() ) )
                        {
                            Xpp3Dom originalConfiguration = ( Xpp3Dom ) plugin.getConfiguration();
                            PluginParameterExpressionEvaluator evaluator = 
                                new PluginParameterExpressionEvaluator( session );
                            String sourceFileName = evaluate( originalConfiguration, evaluator, "sourceFile" );
                            String filenameWithoutExtension =
                                FileUtils.removeExtension( FileUtils.removePath( sourceFileName, '/' ) );
                            sourceFile = getFileFromDirectory( sourceFile, filenameWithoutExtension );
                        }
                    }
                    }

                copy( sourceFile, destFile );
                if ( copyRSL || copyRuntimeLocales )
                {
                    performSubArtifactsCopy( artifact );
                }
            }

            List<Artifact> airDependencies = getAirArtifacts();

            for ( Artifact artifact : airDependencies )
            {
                File sourceFile = artifact.getFile();
                File destFile = getDestinationFile( artifact );

                copy( sourceFile, destFile );
            }
        }
        // If executed in a swf project, copy the related files to the target directory.
        // This should only be needed for testing and debugging applications in your IDE.
        else if( "swf".equals( packaging ) )
        {
            // In case of a swf packaging, the resources are copied
            // to the default output directory.
            webappDirectory = new File(project.getBuild().getDirectory());

            if ( copyRSL || copyRuntimeLocales )
            {
                if ( copyRSL )
                {
                    performRslCopy( project );
                }
                if ( copyRuntimeLocales )
                {
                    performRuntimeLocalesCopy( project );
                }
            }
        }
        else
        {
            getLog().warn( "'copy-flex-resources' is intended to run on war or swf projects" );
        }

    }
    
    /**
     * Short-hand method for evaluating a configuration value.
     * @return the configuration value if defined, or the default value if not.
     */
    private String evaluate( Xpp3Dom configuration, PluginParameterExpressionEvaluator evaluator, final String name )
    {
        try
        {
            final Xpp3Dom child = configuration.getChild( name );
            if ( child.getValue() != null )
            {
                return ( String ) evaluator.evaluate( child.getValue() );
            }
            else
            {
                return ( String ) evaluator.evaluate( child.getAttribute( "default-value" ) );
            }
        }
        catch ( final Exception e )
        {
            return null;
        }
    }
    
    /**
     * In case we have a directory instead of a file (e.g. Eclipse hot deploy), we search for the result file inside the
     * directory
     * @param sourceDirectory
     * @return
     */
    private File getFileFromDirectory( File sourceDirectory, String filenameWithoutExtension )
    {
        File sourceFile = new File( sourceDirectory, filenameWithoutExtension + ".swc" );
        if ( sourceFile.exists() )
        {
            try
            {
                UnArchiver unarchive = archiverManager.getUnArchiver( sourceFile );
                unarchive.setSourceFile( sourceFile );
                unarchive.setDestDirectory( sourceDirectory );
                unarchive.extract();
                sourceFile = new File( sourceDirectory, "library.swf" );
            }
            catch ( Exception e )
            {
                throw new MavenRuntimeException( "Failed to extract " + sourceFile, e );
            }
        }
        else
        {
            sourceFile = new File( sourceDirectory, filenameWithoutExtension + ".swf" );
        }

        return sourceFile;
    }

    private List<Artifact> getAirArtifacts()
    {
        return getArtifacts( AIR, project );
    }

    private List<Artifact> getArtifacts( String type, MavenProject project )
    {
        List<Artifact> swfArtifacts = new ArrayList<Artifact>();
        Set<Artifact> artifacts = project.getArtifacts();
        for ( Artifact artifact : artifacts )
        {
            if ( type.equals( artifact.getType() ) )
            {
                swfArtifacts.add( artifact );
            }
        }
        return swfArtifacts;
    }

    private File getDestinationFile( Artifact artifact )
        throws MojoExecutionException
    {
        boolean isModule = !StringUtils.isEmpty( artifact.getClassifier() );
        MavenProject pomProject = getProject( artifact );
        String fileName;
        if ( isModule )
        {
            if ( !stripModuleArtifactInfo )
            {
                fileName =
                    artifact.getArtifactId() + "-" + artifact.getVersion() + "-" + artifact.getClassifier() + "."
                        + artifact.getType();
            }
            else
            {
                fileName = artifact.getClassifier() + "." + artifact.getType();
            }
        }
        else
        {
            if ( !useFinalName )
            {
                fileName = artifact.getArtifactId() + "-" + artifact.getVersion() + "." + artifact.getType();
            }
            else
            {
                fileName = pomProject.getBuild().getFinalName() + "." + artifact.getType();
            }
        }

        if ( stripVersion && fileName.contains( artifact.getVersion() ) )
        {
            fileName = fileName.replace( "-" + artifact.getVersion(), "" );
        }

        File destFile = new File( webappDirectory, fileName );

        return destFile;
    }

    private MavenProject getProject( Artifact artifact )
        throws MojoExecutionException
    {
        try
        {
            ProjectBuildingRequest request = 
                compatibilityHelper.getProjectBuildingRequest( session, localRepository, remoteRepositories );
            ArrayList<String> ids = new ArrayList<String>();
            for ( Profile profile : project.getActiveProfiles() ){
            	ids.add( profile.getId() );
            }
            request.setActiveProfileIds(ids);
            return projectBuilder.build( artifact, request ).getProject();
        }
        catch ( ProjectBuildingException e )
        {
            getLog().warn( "Failed to retrieve pom for " + artifact );
            return null;
        }
    }

    private List<Artifact> getRSLDependencies( MavenProject artifactProject )
    {
        List<Artifact> swcDeps = getArtifacts( SWC, artifactProject );
        for ( Iterator<Artifact> iterator = swcDeps.iterator(); iterator.hasNext(); )
        {
            Artifact artifact = (Artifact) iterator.next();
            if ( !( RSL.equals( artifact.getScope() ) || CACHING.equals( artifact.getScope() ) ) )
            {
                iterator.remove();
            }
        }
        return swcDeps;
    }

    private String getLastRslUrls( MavenProject artifactProject )
    {
        String[] urls = CompileConfigurationLoader.getCompilerPluginSettings( artifactProject, "rslUrls" );
        if ( urls == null || urls.length == 0 )
        {
            urls = AbstractMavenMojo.DEFAULT_RSL_URLS;
        }
        return urls[urls.length - 1];
    }

    private String getRuntimeLocaleOutputPath( MavenProject artifactProject )
    {
        String runtimeLocaleOutputPath =
            CompileConfigurationLoader.getCompilerPluginSetting( artifactProject, "runtimeLocaleOutputPath" );
        if ( runtimeLocaleOutputPath == null )
        {
            runtimeLocaleOutputPath = AbstractMavenMojo.DEFAULT_RUNTIME_LOCALE_OUTPUT_PATH;
        }
        return runtimeLocaleOutputPath;
    }

    private List<Artifact> getRuntimeLocalesDependencies( MavenProject artifactProject )
    {
        String[] localesRuntime =
            CompileConfigurationLoader.getCompilerPluginSettings( artifactProject, "localesRuntime" );
        if ( localesRuntime == null || localesRuntime.length == 0 )
        {
            return Collections.emptyList();
        }

        List<Artifact> artifacts = new ArrayList<Artifact>();
        for ( String locale : localesRuntime )
        {
            artifacts.add( repositorySystem.createArtifactWithClassifier( artifactProject.getGroupId(),
                                                                          artifactProject.getArtifactId(),
                                                                          artifactProject.getVersion(), SWF, locale ) );
        }
        return artifacts;
    }

    private List<Artifact> getSwfArtifacts()
    {
        return getArtifacts( SWF, project );
    }

    private void performRslCopy( MavenProject artifactProject )
        throws MojoExecutionException
    {
        List<Artifact> rslDeps = getRSLDependencies( artifactProject );

        if ( rslDeps.isEmpty() )
        {
            return;
        }

        String rslUrls = getLastRslUrls( artifactProject );

        for ( Artifact artifact : rslDeps )
        {
            String extension;
            if ( RSL.equals( artifact.getScope() ) )
            {
                extension = SWF;
            }
            else
            {
                extension = SWZ;
            }

            Artifact rslArtifact =
                repositorySystem.createArtifactWithClassifier( artifact.getGroupId(), artifact.getArtifactId(),
                        artifact.getVersion(), extension, artifact.getClassifier() );
            rslArtifact = replaceWithResolvedArtifact( rslArtifact );

            File destFile = resolveRslDestination( rslUrls, artifact, extension );
            File sourceFile = rslArtifact.getFile();
            copy( sourceFile, destFile );
        }
    }

    private void performRuntimeLocalesCopy( MavenProject artifactProject )
        throws MojoExecutionException
    {
        List<Artifact> deps = getRuntimeLocalesDependencies( artifactProject );

        if ( deps.isEmpty() )
        {
            return;
        }

        String runtimeLocaleOutputPath = getRuntimeLocaleOutputPath( artifactProject );

        for ( Artifact artifact : deps )
        {
            artifact = replaceWithResolvedArtifact( artifact );
            copy( artifact.getFile(), resolveRuntimeLocaleDestination( runtimeLocaleOutputPath, artifact ) );
        }
    }

    private void performSubArtifactsCopy( Artifact artifact )
        throws MojoExecutionException
    {
        MavenProject artifactProject = getProject( artifact );
        if ( artifactProject != null )
        {
            if ( copyRSL )
            {
                performRslCopy( artifactProject );
            }
            if ( copyRuntimeLocales )
            {
                performRuntimeLocalesCopy( artifactProject );
            }
        }
    }

    private String replaceContextRoot( String sample )
    {
        String absoluteWebappPath = webappDirectory.getAbsolutePath();
        if ( sample.contains( "/{contextRoot}" ) )
        {
            sample = sample.replace( "/{contextRoot}", absoluteWebappPath );
        }
        else
        {
            sample = absoluteWebappPath + "/" + sample;
        }

        return sample;
    }

    private Artifact replaceWithResolvedArtifact( Artifact artifact )
        throws MojoExecutionException
    {
        return resolve( artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                        artifact.getClassifier(), artifact.getType() );
    }

    private File resolveRslDestination( String rsl, Artifact artifact, String extension )
    {
        rsl = replaceContextRoot( rsl );
        rsl = MavenUtils.interpolateRslUrl( rsl, artifact, extension, null );
        return file( rsl );
    }

    private File resolveRuntimeLocaleDestination( String runtimeLocaleOutputPath, Artifact artifact )
    {
        String path = replaceContextRoot( runtimeLocaleOutputPath );
        path = MavenUtils.getRuntimeLocaleOutputPath( path, artifact, artifact.getClassifier(), SWF );

        return new File( path ).getAbsoluteFile();
    }

}
